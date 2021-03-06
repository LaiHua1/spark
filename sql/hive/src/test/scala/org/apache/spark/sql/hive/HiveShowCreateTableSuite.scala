/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import org.apache.spark.sql.{AnalysisException, ShowCreateTableSuite}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.internal.{HiveSerDe, SQLConf}

class HiveShowCreateTableSuite extends ShowCreateTableSuite with TestHiveSingleton {

  private var origCreateHiveTableConfig = false

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    origCreateHiveTableConfig =
      SQLConf.get.getConf(SQLConf.LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT_ENABLED)
    SQLConf.get.setConf(SQLConf.LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT_ENABLED, true)
  }

  protected override def afterAll(): Unit = {
    SQLConf.get.setConf(SQLConf.LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT_ENABLED,
      origCreateHiveTableConfig)
    super.afterAll()
  }

  test("view") {
    withView("v1") {
      sql("CREATE VIEW v1 AS SELECT 1 AS a")
      checkCreateHiveTableOrView("v1", "VIEW")
    }
  }

  test("view  with output columns") {
    withView("v1") {
      sql("CREATE VIEW v1 (b) AS SELECT 1 AS a")
      checkCreateHiveTableOrView("v1", "VIEW")
    }
  }

  test("simple hive table") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |TBLPROPERTIES (
           |  'prop1' = 'value1',
           |  'prop2' = 'value2'
           |)
         """.stripMargin
      )

      checkCreateHiveTableOrView("t1")
    }
  }

  test("simple external hive table") {
    withTempDir { dir =>
      withTable("t1") {
        sql(
          s"""CREATE TABLE t1 (
             |  c1 INT COMMENT 'bla',
             |  c2 STRING
             |)
             |LOCATION '${dir.toURI}'
             |TBLPROPERTIES (
             |  'prop1' = 'value1',
             |  'prop2' = 'value2'
             |)
           """.stripMargin
        )

        checkCreateHiveTableOrView("t1")
      }
    }
  }

  test("partitioned hive table") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |COMMENT 'bla'
           |PARTITIONED BY (
           |  p1 BIGINT COMMENT 'bla',
           |  p2 STRING
           |)
         """.stripMargin
      )

      checkCreateHiveTableOrView("t1")
    }
  }

  test("hive table with explicit storage info") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
           |COLLECTION ITEMS TERMINATED BY '@'
           |MAP KEYS TERMINATED BY '#'
           |NULL DEFINED AS 'NaN'
         """.stripMargin
      )

      checkCreateHiveTableOrView("t1")
    }
  }

  test("hive table with STORED AS clause") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |STORED AS PARQUET
         """.stripMargin
      )

      checkCreateHiveTableOrView("t1")
    }
  }

  test("hive table with serde info") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |ROW FORMAT SERDE 'org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe'
           |WITH SERDEPROPERTIES (
           |  'mapkey.delim' = ',',
           |  'field.delim' = ','
           |)
           |STORED AS
           |  INPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat'
           |  OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat'
         """.stripMargin
      )

      checkCreateHiveTableOrView("t1")
    }
  }

  test("hive bucketing is supported") {
    withTable("t1") {
      sql(
        s"""CREATE TABLE t1 (a INT, b STRING)
           |CLUSTERED BY (a)
           |SORTED BY (b)
           |INTO 2 BUCKETS
         """.stripMargin
      )
      checkCreateHiveTableOrView("t1")
    }
  }

  test("hive partitioned view is not supported") {
    withTable("t1") {
      withView("v1") {
        sql(
          s"""
             |CREATE TABLE t1 (c1 INT, c2 STRING)
             |PARTITIONED BY (
             |  p1 BIGINT COMMENT 'bla',
             |  p2 STRING )
           """.stripMargin)

        createRawHiveTable(
          s"""
             |CREATE VIEW v1
             |PARTITIONED ON (p1, p2)
             |AS SELECT * from t1
           """.stripMargin
        )

        val cause = intercept[AnalysisException] {
          sql("SHOW CREATE TABLE v1")
        }

        assert(cause.getMessage.contains(" - partitioned view"))

        val causeForSpark = intercept[AnalysisException] {
          sql("SHOW CREATE TABLE v1 AS SERDE")
        }

        assert(causeForSpark.getMessage.contains(" - partitioned view"))
      }
    }
  }

  test("SPARK-24911: keep quotes for nested fields in hive") {
    withTable("t1") {
      val createTable = "CREATE TABLE `t1` (`a` STRUCT<`b`: STRING>) USING hive"
      sql(createTable)
      val shownDDL = getShowDDL("SHOW CREATE TABLE t1")
      assert(shownDDL == "CREATE TABLE `default`.`t1` (`a` STRUCT<`b`: STRING>)")

      checkCreateHiveTableOrView("t1")
    }
  }

  /**
   * This method compares the given table with the table created by the DDL generated by
   * `SHOW CREATE TABLE AS SERDE`.
   */
  private def checkCreateHiveTableOrView(tableName: String, checkType: String = "TABLE"): Unit = {
    val table = TableIdentifier(tableName, Some("default"))
    val db = table.database.getOrElse("default")
    val expected = spark.sharedState.externalCatalog.getTable(db, table.table)
    val shownDDL = sql(s"SHOW CREATE TABLE ${table.quotedString} AS SERDE").head().getString(0)
    sql(s"DROP $checkType ${table.quotedString}")

    try {
      sql(shownDDL)
      val actual = spark.sharedState.externalCatalog.getTable(db, table.table)
      checkCatalogTables(expected, actual)
    } finally {
      sql(s"DROP $checkType IF EXISTS ${table.table}")
    }
  }

  private def createRawHiveTable(ddl: String): Unit = {
    hiveContext.sharedState.externalCatalog.unwrapped.asInstanceOf[HiveExternalCatalog]
      .client.runSqlHive(ddl)
  }

  private def checkCreateSparkTableAsHive(tableName: String): Unit = {
    val table = TableIdentifier(tableName, Some("default"))
    val db = table.database.get
    val hiveTable = spark.sharedState.externalCatalog.getTable(db, table.table)
    val sparkDDL = sql(s"SHOW CREATE TABLE ${table.quotedString}").head().getString(0)
    // Drops original Hive table.
    sql(s"DROP TABLE ${table.quotedString}")

    try {
      // Creates Spark datasource table using generated Spark DDL.
      sql(sparkDDL)
      val sparkTable = spark.sharedState.externalCatalog.getTable(db, table.table)
      checkHiveCatalogTables(hiveTable, sparkTable)
    } finally {
      sql(s"DROP TABLE IF EXISTS ${table.table}")
    }
  }

  private def checkHiveCatalogTables(hiveTable: CatalogTable, sparkTable: CatalogTable): Unit = {
    def normalize(table: CatalogTable): CatalogTable = {
      val nondeterministicProps = Set(
        "CreateTime",
        "transient_lastDdlTime",
        "grantTime",
        "lastUpdateTime",
        "last_modified_by",
        "last_modified_time",
        "Owner:",
        // The following are hive specific schema parameters which we do not need to match exactly.
        "totalNumberFiles",
        "maxFileSize",
        "minFileSize"
      )

      table.copy(
        createTime = 0L,
        lastAccessTime = 0L,
        properties = table.properties.filterKeys(!nondeterministicProps.contains(_)),
        stats = None,
        ignoredProperties = Map.empty,
        storage = table.storage.copy(properties = Map.empty),
        provider = None,
        tracksPartitionsInCatalog = false
      )
    }

    def fillSerdeFromProvider(table: CatalogTable): CatalogTable = {
      table.provider.flatMap(HiveSerDe.sourceToSerDe(_)).map { hiveSerde =>
        val newStorage = table.storage.copy(
          inputFormat = hiveSerde.inputFormat,
          outputFormat = hiveSerde.outputFormat,
          serde = hiveSerde.serde
        )
        table.copy(storage = newStorage)
      }.getOrElse(table)
    }

    assert(normalize(fillSerdeFromProvider(sparkTable)) == normalize(hiveTable))
  }

  test("simple hive table in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 STRING COMMENT 'bla',
           |  c2 STRING
           |)
           |TBLPROPERTIES (
           |  'prop1' = 'value1',
           |  'prop2' = 'value2'
           |)
           |STORED AS orc
         """.stripMargin
      )

      checkCreateSparkTableAsHive("t1")
    }
  }

  test("show create table as serde can't work on data source table") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 STRING COMMENT 'bla',
           |  c2 STRING
           |)
           |USING orc
         """.stripMargin
      )

      val cause = intercept[AnalysisException] {
        checkCreateHiveTableOrView("t1")
      }

      assert(cause.getMessage.contains("Use `SHOW CREATE TABLE` without `AS SERDE` instead"))
    }
  }

  test("simple external hive table in Spark DDL") {
    withTempDir { dir =>
      withTable("t1") {
        sql(
          s"""
             |CREATE TABLE t1 (
             |  c1 STRING COMMENT 'bla',
             |  c2 STRING
             |)
             |LOCATION '${dir.toURI}'
             |TBLPROPERTIES (
             |  'prop1' = 'value1',
             |  'prop2' = 'value2'
             |)
             |STORED AS orc
           """.stripMargin
        )

        checkCreateSparkTableAsHive("t1")
      }
    }
  }

  test("hive table with STORED AS clause in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |STORED AS PARQUET
         """.stripMargin
      )

      checkCreateSparkTableAsHive("t1")
    }
  }

  test("hive table with nested fields with STORED AS clause in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING,
           |  c3 STRUCT <s1: INT, s2: STRING>
           |)
           |STORED AS PARQUET
         """.stripMargin
      )

      checkCreateSparkTableAsHive("t1")
    }
  }

  test("hive table with unsupported fileformat in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |STORED AS RCFILE
         """.stripMargin
      )

      val cause = intercept[AnalysisException] {
        checkCreateSparkTableAsHive("t1")
      }

      assert(cause.getMessage.contains("unsupported serde configuration"))
    }
  }

  test("hive table with serde info in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 INT COMMENT 'bla',
           |  c2 STRING
           |)
           |ROW FORMAT SERDE 'org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe'
           |STORED AS
           |  INPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat'
           |  OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat'
         """.stripMargin
      )

      checkCreateSparkTableAsHive("t1")
    }
  }

  test("hive view is not supported by show create table without as serde") {
    withTable("t1") {
      withView("v1") {
        sql("CREATE TABLE t1 (c1 STRING, c2 STRING)")

        createRawHiveTable(
          s"""
             |CREATE VIEW v1
             |AS SELECT * from t1
           """.stripMargin
        )

        val cause = intercept[AnalysisException] {
          sql("SHOW CREATE TABLE v1")
        }

        assert(cause.getMessage.contains("view isn't supported"))
      }
    }
  }

  test("partitioned, bucketed hive table in Spark DDL") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  emp_id INT COMMENT 'employee id', emp_name STRING,
           |  emp_dob STRING COMMENT 'employee date of birth', emp_sex STRING COMMENT 'M/F'
           |)
           |COMMENT 'employee table'
           |PARTITIONED BY (
           |  emp_country STRING COMMENT '2-char code', emp_state STRING COMMENT '2-char code'
           |)
           |CLUSTERED BY (emp_sex) SORTED BY (emp_id ASC) INTO 10 BUCKETS
           |STORED AS ORC
         """.stripMargin
      )

      checkCreateSparkTableAsHive("t1")
    }
  }

  test("show create table for transactional hive table") {
    withTable("t1") {
      sql(
        s"""
           |CREATE TABLE t1 (
           |  c1 STRING COMMENT 'bla',
           |  c2 STRING
           |)
           |TBLPROPERTIES (
           |  'transactional' = 'true',
           |  'prop1' = 'value1',
           |  'prop2' = 'value2'
           |)
           |CLUSTERED BY (c1) INTO 10 BUCKETS
           |STORED AS ORC
         """.stripMargin
      )


      val cause = intercept[AnalysisException] {
        sql("SHOW CREATE TABLE t1")
      }

      assert(cause.getMessage.contains(
        "SHOW CREATE TABLE doesn't support transactional Hive table"))
    }
  }
}
