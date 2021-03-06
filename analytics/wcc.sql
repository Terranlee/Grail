DROP TABLE IF EXISTS cur_alias;

DROP TABLE IF EXISTS cur;

DROP TABLE IF EXISTS message;

DROP TABLE IF EXISTS next;

DROP TABLE IF EXISTS out_cnts;

DROP TABLE IF EXISTS in_cnts;

DROP TABLE IF EXISTS toupdate;

DROP INDEX IF EXISTS idx_src;

DROP INDEX IF EXISTS idx_dest;

DROP TABLE IF EXISTS next;
 CREATE TABLE next AS
SELECT id AS id, CAST(2147483647 AS INT) AS  val
FROM vertex
;

CREATE TABLE message(
 id int,
 val INT
);

INSERT INTO message
(SELECT *, CAST(id as INT)
FROM vertex
);

CREATE INDEX idx_dest ON edge(dest);

CREATE INDEX idx_src ON edge(src);

DO $$
DECLARE

flag integer := -1;

isFirst integer := 1;
BEGIN

WHILE flag != 0 LOOP

 IF (isFirst = 1)
 THEN
 DROP TABLE IF EXISTS cur;
 CREATE TABLE cur AS
SELECT message.id AS id, MIN(message.val) AS val
 FROM message
 GROUP BY id
 ;
 isFirst := 0;
 ELSE
 DROP TABLE IF EXISTS message;
 CREATE TABLE message AS
SELECT id, MIN(val) as val 
 FROM (
  
SELECT edge.src AS id, toupdate.val AS val
  FROM toupdate, edge
  WHERE edge.dest = toupdate.id 
    UNION ALL
  
SELECT edge.dest AS id, toupdate.val AS val
  FROM toupdate, edge
  WHERE edge.src = toupdate.id 
   ) AS Temp
 ;
 GROUP BY id
 ;
 END IF;

 DROP TABLE IF EXISTS cur;
 CREATE TABLE cur AS
SELECT message.id AS id, MIN(message.val) AS val
 FROM message
 GROUP BY id
 ;

 DROP TABLE IF EXISTS message;

 DROP TABLE IF EXISTS toupdate;
 CREATE TABLE toupdate AS
SELECT cur.id AS id, cur.val AS val
 FROM cur, next
 WHERE cur.id = next.id  AND cur.val<next.val
 ;

 UPDATE next SET 
 val=toupdate.val
 FROM 
 toupdate
 WHERE 
 next.id=toupdate.id
 ;

 DROP TABLE IF EXISTS message;
 CREATE TABLE message AS
SELECT id, MIN(val) as val 
 FROM (
  
SELECT edge.src AS id, toupdate.val AS val
  FROM toupdate, edge
  WHERE edge.dest = toupdate.id 
    UNION ALL
  
SELECT edge.dest AS id, toupdate.val AS val
  FROM toupdate, edge
  WHERE edge.src = toupdate.id 
   ) AS Temp
 ;
 GROUP BY id
 ;

 DROP TABLE IF EXISTS cur;

 flag := (SELECT COUNT (*) FROM toupdate);
END LOOP;
 
END $$;

