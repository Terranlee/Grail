
from Blocks.Block import Block
from Blocks.CreateTableBlock import CreateTableBlock
from Blocks.DropIndexBlock import DropIndexBlock
from Blocks.DropTableBlock import DropTableBlock
from Blocks.DeclareBlock import DeclareBlock
from Blocks.InsertBlock import InsertBlock
from Blocks.SelectIntoBlock import SelectIntoBlock
from Blocks.SelectIntoExtended import SelectIntoExtended
from Blocks.UpdateVertexBlock import UpdateVertexBlock
from Blocks.BeginWhileBlock import BeginWhileBlock

from Blocks.EndWhileBlock import EndWhileBlock 

import re
from TbNameGen import TbNameGen
from CommonDefs import CommonDefs

class Translator():
    options = {}
    convertedOptions = {}
    blocks = []
    tableNameList = []
    indentLevel = -1
    senders = []
    BEGIN_IF = 0
    ASSIGNMENT = 1
    MUTATE_VALUE = 2
    SEND_MSG = 3
    END_IF = 4
    
    def __init__(self, options):
        self.options = options
        self.indentLevel = 0
    
    
    def getBlocks(self):    
        '''
        returns the generated sql code blocks
        '''
        return self.blocks
    def getSenders(self):
        '''
        return senders list (table names
        '''
        return self.senders
    def getConvertedOptions(self):
        '''
        returns the parsed converted options
        '''
        return self.convertedOptions
    
    def beginWhile(self):
        '''
        generate block and append for begin of while
        '''
        endStr=self.options["End"]
        if(endStr!="NO_MESSAGE"):
            initval=endStr[endStr.index('(')+1:endStr.index(')')].split(",")[1];
        else:
            initval="-1"
        declaration=""
        declaration+="flag integer := "+initval+";"    
        loopCondition="flag != 0"    
        self.blocks.append(DeclareBlock("declare",self.indentLevel,declaration))
        self.blocks.append(BeginWhileBlock("beginWhile",
                                   self.indentLevel,
                                   loopCondition))
        self.indentLevel = self.indentLevel + 1
    
    def endWhile(self):
        '''
        generates the block for end of while
        '''
        for val in self.tableNameList:
            if(val == "message" or val == "next" or val == "in_cnts" or val == "out_cnts" or val=="toupdate"):
                continue
            self.blocks.append(DropTableBlock("drop" + val, self.indentLevel, val))
        self.tableNameList = []   
        self.blocks.append(EndWhileBlock("endWhile", self.indentLevel, self.options["End"], "message"))   
            
    def generateCnts(self, isIn):
        '''
        generates the link counts table
        @param isIn: Set to 1 if the table is for incoming edges, 0 otherwise
        @type isIn:  boolean
        '''
        tableNames = ["in_cnts", "out_cnts"]
        attrs = ["src", "dest"]
        fromList = []
        fromList.append("edge")
        attrList = []
        if(isIn == 0):
            attrList.append(attrs[0] + " AS id")
        elif(isIn == 1):
            attrList.append(attrs[1] + " AS id")   
        if(isIn == 0):
            attrList.append("COUNT(" + attrs[1] + ") AS cnt")
        elif(isIn == 1):
            attrList.append("COUNT(" + attrs[0] + ") AS cnt")  
        
        tab = -1
        atrInd = -1
        if(isIn == 0): tab = 1
        elif(isIn == 1): tab = 0
        
        if(isIn == 1): atrInd = 1
        elif(isIn == 0): atrInd = 0
             
        self.blocks.append(SelectIntoExtended("genCnt",
                                        self.indentLevel,
                                        attrList,
                                        tableNames[tab],
                                        fromList,
                                        "",
                                        False,
                                        attrs[atrInd]))        
        self.tableNameList.append(tableNames[tab])
   
    def combineMsg(self):
        '''
        Generate block for doing an aggregation on the messages table based on user input
        '''
        aggregationVal = self.options["CombineMessage"].replace("message", "message.val")
        attrList = []
        attrList.append("message.id AS id")
        attrList.append(aggregationVal + " AS val")
        fromList = []
        fromList.append("message")
        self.blocks.append(SelectIntoExtended("combineMsg",
                                        self.indentLevel,
                                        attrList,
                                        "cur",
                                        fromList,
                                        "",
                                        False,
                                        "id"))
        self.tableNameList.append("cur")
        self.convertedOptions["aggFunc"] = aggregationVal
        
  
    def genTbForVar(self, context, stat):
        '''
        generates a table for a variable
        @param context:table that stores the current visible vertices and their values
        @type context: string
        @param stat: statement from config that is used to construct the sql statement for the evaluation of the variable
        @type stat: string
        '''
        l = stat.index("=")
        varName = stat[0:l].strip()
        exp = stat[l + 1:]
        if(context == "cur"):
            exp = exp.replace("getAggregationVal()", "cur.val")
        else:
            exp = exp.replace("getAggregationVal()", context + ".val")    
        exp = exp.replace("getVal()", "next.val")
        atomics = re.split(" |\\+|-|\\*|/|<|>|(==)|(AND)|(OR)", exp)
        userTbs = []
        userTbsIndex = 0
        
        for item in atomics:
            if(item is None): continue
            item = item.strip()
            if(item == ""): continue
            for var in re.finditer("\.(val)|(id)", item):
                userTbs.append(item[0:var.start()])
        attrList = []
        fromList = []
        tbName = context
        if(len(userTbs) <= 1):
            if(context == "cur"):
                tbName = userTbs[userTbsIndex]
                userTbsIndex = userTbsIndex + 1
                
        attrList.append(tbName + ".id AS id")
        attrList.append(tbName + ".val AS val")
        fromList += userTbs
        self.blocks.append(SelectIntoExtended("genVar",
                                            self.indentLevel,
                                            attrList,
                                            varName,
                                            fromList,
                                            exp,
                                            True,
                                            ""))
        self.tableNameList.append(varName)
    

    def join(self, targetTb, tbList, exp, valFrom):
        '''
        generates blocks that join tables on id        
        @param targetTb: final table to store resukt
        @type targetTb: string
        @param tbList: list of tables joined
        @type tbList:  list of strings
        @param exp: join predicates
        @type exp: string
        @param valFrom: table where the value is from 
        @type valFrom: string
        '''
        attrList = []
        attrList.append(tbList[0] + ".id AS id")
        attrList.append(valFrom + ".val AS val")
        self.blocks.append(SelectIntoExtended("join",
                                        self.indentLevel,
                                        attrList,
                                        targetTb,
                                        tbList,
                                        exp,
                                        True,
                                        ""))
        self.tableNameList.append(targetTb)
    
    
    def sendMsg(self, dirval, content, context):
        '''
        generates the blocks to send messages after each iteration    
        @param dirval: direction of join (decides whether the src or dest id should be used for join)
        @type dirval: string
        @param content: content 
        @type content: string
        @param context: context used in generating send statement predicate
        @type context: string
        '''
        attrs = ["src", "dest"]
        joinStr = ""
        joinFlag = 0
        attrList = []
        fromList = []
        atomics = re.split(" |\\+|-|\\*|/|<|>|(==)|(AND)|(OR)", content)
        userTbs = []
        for item in atomics:
            if(item is None): continue
            item = item.strip()
            if(item == ""): continue
            for var in re.finditer("\.(val)|(id)", item):
                tbName = item[0:var.start()]
                userTbs.append(item[0:var.start()])
                fromList.append(tbName)
                self.senders.append(tbName)
        fromList.append("edge")
        if(content.find("out_cnts") != -1):
            fromList.append("out_cnts")      
            joinFlag |= 0x2 
        if(content.find("in_cnts") != -1):
            fromList.append("in_cnts")
            joinFlag |= 0x1
            
        pred = ""
        if ((joinFlag >> 1) == 1):
            joinStr += " AND out_cnts.id = " + context + ".id"
        if ((joinFlag & 1) == 1):
            joinStr += " AND in_cnts.id = " + context + ".id"
 
        
        if(dirval == "in" or dirval == "out"):
            flag = -1
            if(dirval == "in"):
                flag = 0
            else:
                flag = 1
            attrList.append("edge." + attrs[flag] + " AS id")
            attrList.append(content+ " AS val")
            pred = "edge." + attrs[1 - flag] + " = " + context + ".id" + joinStr
            groupBy = ""
            if(dir == "in"): groupBy = "src"
            else: groupBy = "dest"
            self.blocks.append(SelectIntoExtended("sendMsg",
                                          self.indentLevel,
                                          attrList,
                                          "message",
                                          fromList,
                                          pred,
                                          True,
                                          groupBy))
            
        
        elif(dirval == "all"):
            flag = 0
            attrList.append("edge." + attrs[flag] + " AS id")
            attrList.append(content + " AS val")
            lhs = SelectIntoExtended("genMsg0",
                                                 self.indentLevel + 1,
                                                 attrList,
                                                 "",
                                                 fromList,
                                                 "edge." + attrs[1 - flag]
                                                         + " = " + context + ".id "
                                                         + joinStr,
                                                 False,
                                                 "src")
            flag = 1 - flag
            attrList = []
            attrList.append("edge." + attrs[flag] + " AS id")
            attrList.append(content + " AS val")
            rhs = SelectIntoExtended("genMsg1",
                                                 self.indentLevel + 1,
                                                 attrList,
                                                 "",
                                                 fromList,
                                                 "edge." + attrs[1 - flag]
                                                         + " = " + context + ".id "
                                                         + joinStr,
                                                 False,
                                                 "")
            newAttrList = []
            newAttrList.append("*")
            
            self.blocks.append(SelectIntoBlock("sendMsg",
                                          self.indentLevel,
                                          newAttrList,
                                          "message",
                                          lhs,
                                          rhs,
                                          "UNION ALL"))
        
        self.tableNameList.append("message")
        self.convertedOptions["contentStr"] = content               
          
    def getStatementType(self, stat):
        '''
        returns a predefined int based on the type of statement
        @param stat: type of statement 
        @type stat: string
        '''
        if(stat.startswith("if")): return self.BEGIN_IF
        if(stat.startswith("setVal")): return self.MUTATE_VALUE
        if(stat.startswith("send")): return self.SEND_MSG
        if(stat.startswith("}")): return self.END_IF
        return self.ASSIGNMENT
     
    
    def updateAndSend(self):
        '''
        updates table values and invokes sendmsg (if value changed)
        '''
        statements = self.options["UpdateAndSend"].split('\n')
        contexts = []
        context = "cur"
        newVal = ""
        for i in range(0, len(statements)):
            
            stat = statements[i]
            if(len(stat) == 0): continue
            stat = stat.strip()
            if(self.getStatementType(stat) == self.BEGIN_IF):
                contexts.append(context)
                flowControl = stat[stat.index("(") + 1:stat.index(")")].strip()
                if(context == "cur"):
                    context = flowControl
                else:
                    targetTb = TbNameGen.getNextTbName()
                    tbList = []
                    tbList.append(context)
                    tbList.append(flowControl)
                    self.join(targetTb, tbList, "", flowControl)
                    context = targetTb
                
            elif(self.getStatementType(stat) == self.MUTATE_VALUE):
                newVal = stat[stat.index("(") + 1:stat.rfind(")")].strip()
                newVal = newVal.replace("getAggregationVal()", context + ".val")
                newVal = newVal.replace("getVal()", "next.val")
                #newVal = newVal.replace("cur", "next")
                self.blocks.append(UpdateVertexBlock("setVal",
                                                   self.indentLevel,
                                                   context,
                                                   newVal))
                newVal = newVal.replace("cur", "next")
                self.convertedOptions["setValContext"] = context
                self.convertedOptions["setValNewVal"] = newVal
                if (context == "cur"):
                    self.convertedOptions["isSender"] = "all"
                else:
                    self.convertedOptions["isSender"] = "notAll"
                
            elif(self.getStatementType(stat) == self.SEND_MSG):
                params = stat[stat.index("(") + 1:stat.rfind(')')].split(",")
                for j in range(0, len(params)):
                    params[j] = params[j].strip()  # TODO
                self.convertedOptions["msgDir"] = params[0]
                params[1] = params[1].replace("getVal()", "next.val")
                params[1] = params[1].replace("getAggregationVal()", context + ".val")
                self.sendMsg(params[0], params[1], context)
                self.convertedOptions["SendMsgDir"] = params[0]
                  
            elif(self.getStatementType(stat) == self.END_IF):
                if(len(contexts) > 0):
                    contexts = contexts[:-1]
                    
            elif(self.getStatementType(stat) == self.ASSIGNMENT):
                self.genTbForVar(context, stat)
                

                    
    def dropTable(self, s):   
        '''
        generates block for dropping a table
        @param s:table name
        @type s: string
        '''
        self.blocks.append(DropTableBlock("drop" + s, self.indentLevel, s))  
         
    def superstep(self):
        '''
        invokes a list of functions in each iteration
        '''
        self.combineMsg()
        self.dropTable("message")
        self.tableNameList.remove("message")
        self.updateAndSend()

    def copyVertex(self):
        '''
        generate block to copy data from the vertex table
        '''
        initVal = self.options['InitiateVal']
        if(initVal == "INT_MAX"):
            initVal = CommonDefs.INT_MAX
            
        elif(initVal == "INT_MIN"):
            initVal = CommonDefs.INT_MIN
            
        elif(initVal == "DBL_MAX"):
            initVal = CommonDefs.DBL_MAX
            
        elif(initVal == "DBL_MIN"):
            initVal = CommonDefs.DBL_MIN
            
        attrList = []
        attrList.append("id AS id")
        attrList.append("CAST(" + initVal + " AS " + self.options["VertexValType"] + ")" + " AS val")
        fromList = []
        fromList.append("vertex")
        self.blocks.append(SelectIntoExtended("copyVertex",
                                        self.indentLevel,
                                        attrList,
                                        "next",
                                        fromList,
                                        "",
                                        False,
                                        ""))
        self.tableNameList.append("next")
 
    def initMsg(self, attrs):
        '''
        generates the block for the generation of the message table initially
        '''
        attrList = []
        fromList = []
        if(attrs[0] == "ALL"):
            attrList.append("*")
            attrList.append(attrs[1])
            fromList.append("vertex")
            self.blocks.append(InsertBlock("initMsg",
                                      self.indentLevel,
                                      "*, " + "CAST(" + attrs[1] + " as " + self.options["MessageValType"] + ")",
                                      "message",
                                      "vertex"))
        else:
            self.blocks.append(Block("initMsg",
                                self.indentLevel,
                                "INSERT INTO message VALUES(" + attrs[0]
                                                              + ", "
                                                              + "CAST(" + attrs[1] + " as " + self.options.get("MessageValType") + ")"
                                                              + ");"))    
    def createTable(self, stage, tbName, attrs):  # @DontTrace
        '''
        generates block for creating a new table
        @param stage: stage string
        @type stage: string
        @param tbName: table name
        @type tbName:string
        @param attrs: attributed list
        @type attrs: list of strings
        '''
        self.blocks.append(CreateTableBlock(stage,
                                         self.indentLevel,
                                         tbName,
                                         attrs))
        self.tableNameList.append(tbName) 
 
    def init(self):
        '''
        performs the initial dropping of preexisting tables
        '''
        self.blocks.append(DropTableBlock("initdropcur", self.indentLevel, "cur"))
        self.blocks.append(DropTableBlock("initdropmsg", self.indentLevel, "message"))
        self.blocks.append(DropTableBlock("initdropnext", self.indentLevel, "next"))
        self.blocks.append(DropTableBlock("initdropoutcnts", self.indentLevel, "out_cnts"))
        self.blocks.append(DropTableBlock("initdroptoupdate", self.indentLevel, "toupdate"))
        self.blocks.append(DropIndexBlock("initdropsrcindex", self.indentLevel, "idx_src", "edge"))
        self.blocks.append(DropIndexBlock("initdropdestindex", self.indentLevel, "idx_dest", "edge"))
        self.copyVertex()
        initMsg = self.options["InitialMessage"]
        initMsg = initMsg[initMsg.index("(") + 1:
                                initMsg.rfind(')')].strip()
        initMsgStrs = initMsg.split(",")
        for i in range(0, len(initMsgStrs)):
            initMsgStrs[i] = initMsgStrs[i].strip()
        attrs = ["id int" , "val " + self.options["MessageValType"]]    
        
        self.createTable("createMsg", "message", attrs)
        self.initMsg(initMsgStrs)
    
        if(self.options["UpdateAndSend"].find("in_cnts") != -1):
            self.generateCnts(True)
        if(self.options["UpdateAndSend"].find("out_cnts") != -1):
            self.generateCnts(False)  
    
            
    def translate(self):
        '''
        generates all the blocks 
        '''
        self.init()
        self.beginWhile()
        self.superstep()
        self.endWhile()
 
