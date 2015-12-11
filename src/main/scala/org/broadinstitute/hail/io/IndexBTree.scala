package org.broadinstitute.hail.io

import java.io.{InputStream, FileInputStream, FileOutputStream}

import org.apache.hadoop.fs.{FSDataInputStream, FSDataOutputStream}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object IndexBTree {

  def write(arr: Array[Long], path: String) {
    // first calculate the necessary number of layers in the tree -- log1024(arr.length) rounded up
    val depth = (math.log10(arr.length) / math.log10(1024)).ceil.toInt

    // now downsample the array -- each downsampling should use the smallest index contained -- 0-1023 should use 0, etc
    val layers = mutable.ArrayBuffer[IndexedSeq[Long]]()
    for (i <- 0 until depth - 1) {
      val multiplier = math.pow(1024, depth - 1 - i).toInt
      println(s"i = $i, mult = $multiplier")
      layers.append((0 until math.pow(1024, i + 1).toInt).map { j =>
        if (j * multiplier <= arr.length)
          arr(j * multiplier)
        else
          -1
      })
    }

    val fs = new FSDataOutputStream(new FileOutputStream(path))
    layers.append(arr)
    layers.zipWithIndex.foreach { case (a, i) => println(s"index $i size is ${a.size}")}
    val flat = layers.flatten
    println("After flatten: " + flat.size)
    val bytes = flat.flatMap(l => Array[Byte](
      (l >>> 56).toByte,
      (l >>> 48).toByte,
      (l >>> 40).toByte,
      (l >>> 32).toByte,
      (l >>> 24).toByte,
      (l >>> 16).toByte,
      (l >>>  8).toByte,
      (l >>>  0).toByte)).toArray
    fs.writeLong(depth)
    fs.write(bytes)
    fs.close()
  }

  def queryArr(start: Long, end: Long, arr: Array[Long]): Array[Long] = {
    println(arr.take(10).mkString(","))
    val depth = arr(0)

    // keep track of depth and position in layer -- position = 1 + (i <- 1 to currentLayer).map(math.pow(1024,_)).sum
    var layerOffset: Int = 0
    var layerPos: Int = 0
    var currentDepth = 1
    def getOffset(i: Int): Int = (0 to i).map(math.pow(1024, _).toInt).sum

    val ret = new mutable.ArrayBuffer[Long]()
    while (currentDepth <= depth) {
      // if not on the last layer, find the largest value <= our start
      if (currentDepth < depth) {
        if (arr(layerOffset + layerPos + 1) > start) {
          layerOffset = getOffset(currentDepth)
          currentDepth += 1
          layerPos = 0
        }
        else
          layerPos += 1
      }
      else {
        if (arr(layerOffset + layerPos + 1) > end)
          currentDepth = Integer.MAX_VALUE
        else if (arr(layerOffset + layerPos + 1) >= start) {
          layerPos += 1
          ret.+=(arr(layerOffset + layerPos))
        }
        else
          layerPos += 1
      }
    }
    ret.toArray
  }

  def query(start: Long, end: Long, in: InputStream): Array[Long] = {
    val fs = new FSDataInputStream(in)
    val depth = fs.readLong()
    println("depth is " + depth)
    println(s"query is ($start - $end)")
    // keep track of depth and position in layer -- position = 1 + (i <- 1 to currentLayer).map(math.pow(1024,_)).sum
    var layerPos: Long = 0
    var currentDepth = 1
    def getOffset(i: Int): Int = (0 to i).map(math.pow(1024, _).toInt).sum * 8
    val ret = new mutable.ArrayBuffer[Long]()

    while (currentDepth <= depth) {
      // if not on the last layer, find the largest value <= our start
      val read = fs.readLong()
      if (currentDepth == 1)
        println(read)

      if (currentDepth < depth) {
        if (read > start) {
          println(s"found read value greater than start: $read -- moving to depth=${currentDepth+1}")
          println(s"layerPos = $layerPos")
          fs.seek(getOffset(currentDepth) + 8192 * (layerPos-1))
          currentDepth += 1
          layerPos = 0
        }
        else {
          layerPos += 1
        }
      }
      else {
        if (read > end || read == -1)
          currentDepth = Integer.MAX_VALUE // exit the loop
        else if (read >= start) {
          println(s"found value in range: $read")
          ret += read
        }
      }
    }
    ret.toArray
  }
}