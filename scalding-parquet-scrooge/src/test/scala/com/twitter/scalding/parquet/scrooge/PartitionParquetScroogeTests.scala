package com.twitter.scalding.parquet.scrooge

import java.io.File

import com.twitter.scalding._
import com.twitter.scalding.parquet.scrooge.thrift_java.test.{Address => TAddress}
import com.twitter.scalding.parquet.scrooge.thrift_scala.test.Address
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.thrift.ThriftParquetReader

import org.scalatest.{Matchers, WordSpec}


object PartitionedParquetScroogeTestSources {
  val path = "/a/path"
  val partitionSource = PartitionParquetScrooge[String, Address](path, "%s")
}

class PartitionedParquetScroogeWriteJob(args: Args) extends Job(args) {
  import PartitionedParquetScroogeTestSources._
  val input = Seq( Address("123 Embarcadero", "94111"), Address("123 E 79th St", "10075"), Address("456 W 80th St", "10075") )

  TypedPipe.from(input)
    .map { case Address(street, zipcode) => (zipcode, Address(street, zipcode)) }
    .write(partitionSource)
}

class PartitionParquetScroogeTests extends WordSpec with Matchers {
  import PartitionedParquetScroogeTestSources._

  def validate(path: Path, expectedAddresses: TAddress*) = {
    val parquetReader: ParquetReader[TAddress] =
      ThriftParquetReader.build(path).withThriftClass(classOf[TAddress]).build()
    Stream.continually(parquetReader.read).takeWhile(_ != null).toArray shouldBe expectedAddresses
  }

  "PartitionParquetScrooge" should {
    "write out partitioned scrooge objects" in {
      var job: Job = null;
      def buildJob(args: Args): Job = {
        job = new PartitionedParquetScroogeWriteJob(args)
        job
      }
      JobTest(buildJob(_))
        .runHadoop
        .finish()

      val testMode = job.mode.asInstanceOf[HadoopTest]

      val directory = new File(testMode.getWritePathFor(partitionSource))

      directory.listFiles().map({ _.getName() }).toSet shouldBe Set("94111", "10075")

      // check that the partitioning is done correctly by zipcode
      validate(new Path(directory.getPath + "/94111/part-00000-00000-m-00000.parquet"),
        new TAddress("123 Embarcadero", "94111"))
      validate(new Path(directory.getPath + "/10075/part-00000-00001-m-00000.parquet"),
        new TAddress("123 E 79th St", "10075"), new TAddress("456 W 80th St", "10075"))
    }
  }
}
