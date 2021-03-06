GRAIL (written in Java) to generate T-SQL
==================================================
This directory contains code (written in Java) to generate a T-SQL program that
can be run against Microsoft's SQL Server. Note this code is not being maintained
going forward as the main GRAIL code base is moving to the Python implementaiton
in the main directory. So, this code is largely to support the results that were
presented in the [original GRAIL paper](http://pages.cs.wisc.edu/~jignesh/publ/Grail.pdf)
that was published in CIDR 2015.

Here are the steps:

1. Clone the git Grail repository into your local machine using one of the
   following commands:
  * `git clone https://github.com/UWQuickstep/Grail`

2. Change your current working directory to `Grail/java`.
  * `cd Grail/java`

3. Change directory to "src" to browse through the code or to compile and run 
   the code.
  * `cd src`

4. Compile the java code using the following command. Note you will need to
   [install Java](https://java.com/en/download/help/index_installing.xml "Java Install Page")
   on your machine.
  * `javac *.java`

5. Run the Grail code, in any of the following ways: (`./config.grail` is used as
   the default configuration file, if no config file is specified)
  * `java Grail` (uses the default configuration file `./config.grail`)
  * `java Grail ../../analytics/pagerank.grail` 
     (to generate the T-SQL script to compute PageRank)
  * `java Grail ../../analytics/sssp.grail` 
     (to generate the T-SQL script to compute the single source shortest path)
  * `java Grail ../../analytics/wcc.grail` 
    (to generate the T-SQL script for weakly connected components)

6. Create the `vertex` and the `edge` table for the algorithm that you would like to run.
   The SQL queries that you can use to generate some sample data can be found in the top
   level directory `datagen`. For example, if you would like to run the weakly connected
   components algorithm, then just open the SQL files `create_and_load_vertex.sql` and 
   `create_and_load_edge.sql` in your Microsoft SQL Server instance, and execute them. 
   A sample `vertex` and `edge` table will be created.

7. Execute the SQL statements generated by Grail (i.e. output of step 5)
   in a Microsoft SQL Server. Note before you do that, you should have the
   graph data loaded into two basic tables: `vertex` and `edge` as explained
   in step 6 . See the [top-level README.md file](../../README.md).
