package unicredit.spark.hbase

import scala.util.control.Exception.allCatch

import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.CellUtil
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.io.ImmutableBytesWritable

import org.apache.hadoop.hbase.util.Bytes

import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL.WithDouble._


trait HBaseReadSupport {
  implicit def toHBaseSC(sc: SparkContext) = new HBaseSC(sc)

  implicit val stringReader = new Reads[String] {
    def read(data: Array[Byte]) = new String(data)
  }

  implicit val jsonReader = new Reads[JValue] {
    def read(data: Array[Byte]) = parse(new String(data))
  }
}

final class HBaseSC(@transient sc: SparkContext) extends Serializable {
  private def extract[A](data: Map[String, List[String]], result: Result, reader: Reads[A]) =
    data map {
      case (cf, columns) =>
        val content = columns flatMap { column =>
          val cell = result.getColumnLatestCell(cf.getBytes, column.getBytes)

          allCatch opt { CellUtil.cloneValue(cell) } map { value =>
            column -> reader.read(value)
          }
        } toMap

        cf -> content
    }

  private def makeConf(config: HBaseConfig, table: String) = {
    val conf = config.get

    conf.set(TableInputFormat.INPUT_TABLE, table)
    conf
  }

  def hbase[A](table: String, data: Map[String, List[String]])
    (implicit config: HBaseConfig, reader: Reads[A]) =

      sc.newAPIHadoopRDD(makeConf(config, table), classOf[TableInputFormat],
        classOf[ImmutableBytesWritable], classOf[Result]) map {
          case (key, row) =>
            Bytes.toString(key.get) -> extract(data, row, reader)
        }

  def hbaseRaw(table: String)(implicit config: HBaseConfig) =

    sc.newAPIHadoopRDD(makeConf(config, table), classOf[TableInputFormat],
      classOf[ImmutableBytesWritable], classOf[Result]) map {
        case (key, row) =>
          Bytes.toString(key.get) -> row
      }

}