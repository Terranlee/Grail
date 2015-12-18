/*
Copyright (c) 2015,
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the University of Wisconsin-Madison nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL JING FAN, ADALBERT GERALD SOOSAI RAJ, AND JIGNESH
M. PATEL BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Block.BeginWhileBlock;
import Block.Block;
import Block.CreateTableBlock;
import Block.DropTableBlock;
import Block.EndWhileBlock;
import Block.InsertBlock;
import Block.SelectIntoBlock;
import Block.UpdateVertexBlock;

/**
 * @brief This is the translation module of Grail.
 * It will convert the user input into basic (not-optimized) T-SQL.
 */
public class Translator {
  // The options that we fetch from the config file.
  private HashMap<String, String> options = null;
  // Convert options to easy-to-use way. Such as <isIteration, y>
  private HashMap<String, String> convertedOptions
      = new HashMap<String, String>();
  // Generated Sql blocks.
  private ArrayList<Block> blocks = new ArrayList<Block>();
  // Current tables in process.
  private ArrayList<String> tableNameList = new ArrayList<String>();
  // Current indent level.
  private int indentLevel;
  // The tables that contains the information about the message senders.
  private HashSet<String> senders = new HashSet<String>();

  private enum StatementType {
      BEGIN_IF,
      ASSIGNMENT,
      MUTATE_VALUE,
      SEND_MSG,
      END_IF
  }

  /**
   * @brief Constructor.
   * @param options The options generated by the parser.
   */
  public Translator(HashMap<String, String> options) {
    this.options = options;
    this.indentLevel = 0;
  }

  /**
   * @brief Return SQL code blocks.
   * @return SQL code blocks.
   */
  public ArrayList<Block> getBlocks() {
    return this.blocks;
  }

  /**
   * @brief Return senders.
   * @return Senders.
   */
  public HashSet<String> getSenders() {
    return senders;
  }

  /**
   * @brief Return converted options.
   * @return Converted Options.
   */
  public HashMap<String, String> getConvertedOptions() {
    return this.convertedOptions;
  }

  /**
   * @brief Generate begin of while.
   */
  private void beginWhile() {
    blocks.add(new BeginWhileBlock("beginWhile",
                                   this.indentLevel,
                                   this.options.get("End")));
    ++this.indentLevel;
  }

  /**
   * @brief Generate end of while.
   */
  private void endWhile() {
    Iterator<String> iter = this.tableNameList.iterator();
    // Drop all the tables that should only be used within iterations.
    while (iter.hasNext()) {
      String val = iter.next();
      // These tables should be kept as they will be used in the next
      // iteration.
      if (val.equals("message")
       || val.equals("next")
       || val.equals("in_cnts")
       || val.equals("out_cnts")) {
        continue;
      }
      this.blocks.add(new DropTableBlock("drop" + val,
                                         this.indentLevel,
                                         val));
      iter.remove();
    }

    blocks.add(new EndWhileBlock("endWhile",
                                 this.indentLevel,
                                 this.options.get("End"), "message"));
    --this.indentLevel;
  }

  /**
   * @brief Generate link counts table.
   * @param isIn Is the table for in-coming edges.
   */
  private void generateCnts(boolean isIn) {
    String[] tableNames = { "in_cnts", "out_cnts"};
    String[] attrs = {"src", "dest"};

    ArrayList<String> fromList = new ArrayList<String>();
    fromList.add("edge");
    ArrayList<String> attrList = new ArrayList<String>();
    // Decide the attributes according to whether the table is for
    // in-coming edges or out-going edges.
    attrList.add(attrs[isIn ? 1 : 0] + " AS id");
    attrList.add("COUNT(" + attrs[isIn ? 0 : 1] + ") AS cnt");
    this.blocks.add(new SelectIntoBlock("genCnt",  // Stage string.
                                        this.indentLevel,  // Indent level.
                                        attrList,  // Attributes.
                                        // Target table.
                                        tableNames[isIn ? 0 : 1],
                                        fromList, // From tables.
                                        null, // No extra predicates.
                                        false,// Don't join tables with id.
                                        // Group by attribute.
                                        attrs[isIn ? 1 : 0]));
    this.tableNameList.add(tableNames[isIn ? 0 : 1]);
  }

  /**
   * @brief Combine messages, that is do aggregation on the messages.
   */
  private void combineMsg() {
    String aggregationVal = options.get("CombineMessage")
                                .replace("message", "message.val");
    ArrayList<String> attrList = new ArrayList<String>();
    attrList.add("message.id AS id");
    attrList.add(aggregationVal + " AS val");
    ArrayList<String> fromList = new ArrayList<String>();
    fromList.add("message");
    this.blocks.add(new SelectIntoBlock("combineMsg", // The stage string.
                                        this.indentLevel, // The indent level.
                                        attrList, // The attribute list.
                                        "cur",  // The new table name.
                                        fromList,  // The from tables.
                                        null, // No predicates.
                                        false, // Don't join on id.
                                        "id")); // Group by attributes.
    this.tableNameList.add("cur");
    this.convertedOptions.put("aggFunc", aggregationVal);
  }

  /**
   * @brief Generate a table for a variable.
   * @param context The context table, which stores the current visible
   * vertices and their values.
   * @param stat The statement that expresses the value of the variable.
   * @return An ArrayList, the first one should be the var name, the second
   * should be the SQL block.
   */
  private void genTbForVar(String context, String stat) {
    int l = stat.indexOf('=');
    String varName = stat.substring(0, l).trim();
    String exp = stat.substring(l+1).trim();
    if (context.equals("cur")) {
        exp = exp.replace("getAggregationVal()", "cur.val");
    } else {
        exp = exp.replace("getAggregationVal()", context+".val");
    }

    exp = exp.replace("getVal()", "next.val");

    // Calculate the number of tables that have appeared.
    String atomics[] = exp.split(" |\\+|-|\\*|/|<|>|(==)|(AND)|(OR)");
    Pattern p = Pattern.compile("\\.(val)|(id)");

    HashSet<String> usedTbs = new HashSet<String>();
    for (String item : atomics) {
      item = item.trim();
      if (item.equals("")) continue;
      Matcher m = p.matcher(item);
      // System.out.println(item);
      while (m.find()) {
        // Mark relevant tables.
        usedTbs.add(item.substring(0, m.start()));
      }
    }
    // @Note: currently scalar operation on variable is not support.
    // For example, tmp = cur.val - 3, because this
    // one can be directly integrated into other operations by using UDF.
    ArrayList<String> attrList = new ArrayList<String>();
    ArrayList<String> fromList = new ArrayList<String>();

    String tbName = context;
    if (usedTbs.size() <= 1) {
      if (context.equals("cur")) {
        tbName = usedTbs.iterator().next();
      }
    }
    attrList.add(tbName + ".id AS id");
    attrList.add(tbName + ".val AS val");
    fromList.addAll(usedTbs);
    this.blocks.add(new SelectIntoBlock(// The stage string.
                                        "genVar",
                                        // The indent level.
                                        this.indentLevel,
                                        // The attribute list.
                                        attrList,
                                        // The variable name,
                                        // used as table name.
                                        varName,
                                        // The from tables.
                                        fromList,
                                        // The predicates.
                                        exp,
                                        // Join thest tables on (vertex)id.
                                        true,
                                        // No group by.
                                        null));
    this.tableNameList.add(varName);
  }

  /**
   * @brief Join several tables with id as common attributes.
   * @param targetTb The table to store result.
   * @param tbList The table involved, joined by the common id attributes.
   * @param exp The predicates.
   * @param valFrom The table where value is from.
   */
  private void join(String targetTb,
                    ArrayList<String> tbList,
                    String exp,
                    String valFrom) {
    ArrayList<String> attrList = new ArrayList<String>();
    attrList.add(tbList.get(0) + ".id AS id");
    attrList.add(valFrom + ".val AS val");
    this.blocks.add(new SelectIntoBlock("join",
                                        this.indentLevel,
                                        attrList,
                                        targetTb,
                                        tbList,
                                        exp,
                                        true,
                                        null));
    this.tableNameList.add(targetTb);
  }

  /**
   * @brief Send messages. The way of join will depend on the send direction.
   * For example, if send to out-going neighbours, should join on edge.src; if
   * send to all neighbours, a union should be used.
   * @param dir The direction.
   * @param content The content.
   * @param context The context of the send statement.
   */
  private void sendMsg(String dir, String content, String context) {
    String[] attrs = {"src", "dest"};
    String joinStr = "";
    // The way of join. 0 means no in_cnts and out_cnts are used. 1 means only
    // in_cnts is used. 2 means only out_cnts is used. 3 means both are used.
    int joinFlag = 0;
    ArrayList<String> attrList = new ArrayList<String>();
    ArrayList<String> fromList = new ArrayList<String>();

    String atomics[] = content.split(" |\\+|-|\\*|/|<|>|(==)|(AND)|(OR)");
    Pattern p = Pattern.compile("\\.(val)|(id)");

    HashSet<String> usedTbs = new HashSet<String>();
    for (String item : atomics) {
      item = item.trim();
      if (item.equals("")) continue;
      Matcher m = p.matcher(item);
      // System.out.println(item);
      while (m.find()) {
        // Mark relevant tables.
        String tbName = item.substring(0, m.start());
        usedTbs.add(item.substring(0, m.start()));
        fromList.add(tbName);
        this.senders.add(tbName);
      }
    }

    fromList.add("edge");
    if (content.contains("out_cnts")) {
      fromList.add("out_cnts");
      joinFlag |= 0x2;
    }
    if (content.contains("in_cnts")) {
      fromList.add("in_cnts");
      joinFlag |= 0x1;
    }
    String pred = null;
    if ((joinFlag >> 1) == 1) {
      joinStr += " AND out_cnts.id = " + context + ".id";
    }
    if ((joinFlag & 1) == 1) {
      joinStr += " AND in_cnts.id = " + context + ".id";
    }

    switch (dir) {
    // Only send to in neighbours and out neighbours.
    case "in": case "out": {
      int flag = dir.equals("in") ? 0 : 1;
      attrList.add(attrs[flag] + " AS id");
      attrList.add(content + " AS val");
      pred = "edge." + attrs[1-flag] + " = " + context + ".id" + joinStr;
      String groupBy = dir.equals("in") ? "src" : "dest";
      this.blocks.add(new SelectIntoBlock("sendMsg",
                                          this.indentLevel,
                                          attrList,
                                          "message",
                                          fromList,
                                          pred,
                                          true,
                                          groupBy));
      break;
    }
    // Send to all neighbours.
    case "all": {
      int flag = 0;
      attrList.add(attrs[flag] + " AS id");
      attrList.add(content + " AS val");
      SelectIntoBlock lhs  = new SelectIntoBlock("genMsg0",
                                                 this.indentLevel + 1,
                                                 attrList,
                                                 null,
                                                 fromList,
                                                 "edge." + attrs[1-flag]
                                                         + " = "+context+".id "
                                                         + joinStr,
                                                 false,
                                                 "src");
      flag = 1 - flag;
      attrList.clear();
      attrList.add(attrs[flag] + " AS id");
      attrList.add(content + " AS val");
      SelectIntoBlock rhs = new SelectIntoBlock("genMsg1",
                                                 this.indentLevel + 1,
                                                 attrList,
                                                 null,
                                                 fromList,
                                                 "edge." + attrs[1-flag]
                                                         + " = "+context+".id "
                                                         + joinStr,
                                                 false,
                                                 "dest");
      ArrayList<String> newAttrList = new ArrayList<String>();
      newAttrList.add("*");
      this.blocks.add(new SelectIntoBlock("sendMsg", // The stage string.
                                          this.indentLevel, // The indent level.
                                          newAttrList, // The attribute list.
                                          "message", // The new table name.
                                          lhs, // Left table SQL.
                                          rhs, // Right table SQL.
                                          "union all")); // Union operation.
    }
    // Don't send any messages.
    case "no": break;
    }
    this.tableNameList.add("message");
    this.convertedOptions.put("contentStr", content);
  }

  /**
   * @brief Get the type of the statement.
   * @param stat The SQL statement string.
   * @return The type.
   */
  private StatementType getStatementType(String stat) {
      if (stat.startsWith("if")) return StatementType.BEGIN_IF;
      if (stat.startsWith("setVal")) return StatementType.MUTATE_VALUE;
      if (stat.startsWith("send")) return StatementType.SEND_MSG;
      if (stat.startsWith("}")) return StatementType.END_IF;
      return StatementType.ASSIGNMENT;
  }

  /**
   * @brief Update values and send messages.
   */
  private void updateAndSend() {
    String[] statements = options.get("UpdateAndSend").split("\n");
    // Contexts record the current level of table value is used. The "cur" is
    // the default value. If you are wrapped in a if statment, for example,
    // if (update) {}, "update" should be the context.
    Stack<String> contexts = new Stack<String>();
    // New tables generated.
    String context = "cur";
    // setValue() will change newVal.
    String newVal = null;
    for (int i = 0; i < statements.length; ++i) {
      String stat = statements[i];
      switch (this.getStatementType(stat)) {
        case BEGIN_IF: {
          // TODO： Currently the if just support single flow control. But it's
          // easy to support things like
          // if (isBig AND isSmall), if (isBig Or isSmall)
          contexts.push(context);
          String flowControl = stat.substring(stat.indexOf('(')+1,
                                              stat.lastIndexOf(')')).trim();
          if (context.equals("cur")) {
            context = flowControl;
          } else {
            // join context table and flow control table
            String targetTb = TbNameGen.getNextTbName();
            ArrayList<String> tbList = new ArrayList<String>();
            tbList.add(context);
            tbList.add(flowControl);
            this.join(targetTb, tbList, null, flowControl);
            context = targetTb;
          }
          break;
        }
        case MUTATE_VALUE: {
          newVal = stat.substring(stat.indexOf('(')+1, stat.lastIndexOf(')'))
                       .trim();
          newVal = newVal.replace("getAggregationVal()", context + ".val");
          newVal = newVal.replace("getVal()", "next.val");
          blocks.add(new UpdateVertexBlock("setVal",
                                           this.indentLevel,
                                           context,
                                           newVal));
          this.convertedOptions.put("setValContext", context);
          this.convertedOptions.put("setValNewVal", newVal);


          if (context.equals("cur")) {
            this.convertedOptions.put("isSender", "all");
          } else {
            this.convertedOptions.put("isSender", "notAll");
          }
          break;
        }
        case SEND_MSG: {
          String[] params = stat.substring(stat.indexOf('(') + 1,
              stat.lastIndexOf(')')).split(",");
          // record send direction.
          for (int j = 0; j < params.length; ++j) {
            params[j] = params[j].trim();
          }
          this.convertedOptions.put("msgDir", params[0]);
          // params[0] should be either all, in, out or no, which indicates the
          // message sending direction. params[1] should be the message value.
          params[1] = params[1].replace("getVal()", "next.val");
          params[1] = params[1].replace("getAggregationVal()",
                                        context + ".val");
          sendMsg(params[0], params[1], context);
          this.convertedOptions.put("SendMsgDir", params[0]);
          break;
        }
        case END_IF: {
          context = contexts.pop();
          break;
        }
        case ASSIGNMENT: {
          this.genTbForVar(context, stat);
          break;
        }
      }
    }

  }

  /** @brief Drop table.
   * @param s The name of the table.
   */
  private void dropTable(String s) {
    this.blocks.add(new DropTableBlock("drop" + s, this.indentLevel, s));
  }

  /**
   * @brief Work in every iteration.
   */
  private void superstep() {
    this.combineMsg();
    this.dropTable("message");
    this.tableNameList.remove("message");
    this.updateAndSend();
  }

  /**
   * @brief Copy data from vertex table.
   * @return The SQL statement.
   */
  private void copyVertex() {
    String initVal = options.get("InitiateVal");

    switch (initVal) {
      case "INT_MAX": {
        initVal = Common.INT_MAX;
        break;
      }
      case "INT_MIN": {
        initVal = Common.INT_MIN;
        break;
      }
      case "DBL_MAX": {
        initVal = Common.DBL_MAX;
        break;
      }
      case "DBL_MIN": {
        initVal = Common.DBL_MIN;
        break;
      }
    }

    ArrayList<String> attrList = new ArrayList<String>();
    attrList.add("id AS id");
    attrList.add(initVal + " AS val");
    ArrayList<String> fromList = new ArrayList<String>();
    fromList.add("vertex");

    this.blocks.add(new SelectIntoBlock("copyVertex", // The stage string.
                                        this.indentLevel, // The indent level.
                                        attrList, // The attribute list.
                                        "next", // The table name.
                                        fromList, // The from tables.
                                        null, // No predicates.
                                        false, // Don't join table on id.
                                        null)); // No group by.
    this.tableNameList.add("next");
  }

  /**
   * @brief Init message table.
   * @param attrs The attrs of message table.
   */
  private void initMsg(String[] attrs) {
    ArrayList<String> attrList = new ArrayList<String>();
    ArrayList<String> fromList = new ArrayList<String>();
    if (attrs[0].equals("ALL")) {
      attrList.add("*");
      attrList.add(attrs[1]);
      fromList.add("vertex");
      this.blocks.add(new InsertBlock("initMsg", // The stage string.
                                      this.indentLevel, // The indent level.
                                      "*, " + attrs[1], // The attribute string.
                                      "message", // The new table.
                                      "vertex")); // The from table.
    } else {
      this.blocks.add(new Block("initMsg", // The stage string.
                                this.indentLevel, // The indent level.
                                // The SQL.
                                "INSERT INTO message VALUES(" + attrs[0]
                                                              + ", "
                                                              + attrs[1]
                                                              + ")"));
    }

  }

  /**
   * @brief Create table.
   * @param stage The stage string.
   * @param tbName The new table name.
   * @param attrs The attribute list.
   */
  private void createTable(String stage, String tbName, String[] attrs) {
    this.blocks.add(new CreateTableBlock(stage, // The staget string.
                                         this.indentLevel,  // The indent level.
                                         tbName, // The new table name.
                                         attrs)); // The attributes.
    this.tableNameList.add(tbName);
  }
  /**
   * @brief Init.
   */
  private void init() {
    this.blocks.add(
        new DropTableBlock("initdropcur", this.indentLevel, "cur"));
    this.blocks.add(
        new DropTableBlock("initdropmsg", this.indentLevel, "message"));
    this.blocks.add(
        new DropTableBlock("initdropnext", this.indentLevel, "next"));
    this.blocks.add(
        new DropTableBlock("initdropoutcnts", this.indentLevel, "out_cnts"));
    
    this.copyVertex();

    String initMsg = options.get("InitialMessage");
    initMsg = initMsg.substring(initMsg.indexOf('(') + 1,
                                initMsg.lastIndexOf(')')).trim();
    String initMsgStrs[] = initMsg.split(",");
    for (int i = 0; i < initMsgStrs.length; ++i) {
        initMsgStrs[i] = initMsgStrs[i].trim();
    }

    String[] attrs = {"id int" , "val "  + options.get("MessageValType")};
    this.createTable("createMsg", "message", attrs);

    this.initMsg(initMsgStrs);

    // Generate in_cnts table or out_cnts table.
    if (options.get("UpdateAndSend").contains("in_cnts")) {
      this.generateCnts(true);
    }

    if (options.get("UpdateAndSend").contains("out_cnts")) {
      this.generateCnts(false);
    }
  }

  /**
   * @brief Generate SQL.
   */
  public void translate() {
    init();
    beginWhile();
    superstep();
    endWhile();
  }

}
