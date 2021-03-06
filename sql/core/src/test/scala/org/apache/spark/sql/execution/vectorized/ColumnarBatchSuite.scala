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

package org.apache.spark.sql.execution.vectorized

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import org.apache.arrow.vector.NullableIntVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.memory.MemoryMode
import org.apache.spark.sql.{RandomDataGenerator, Row}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.arrow.ArrowUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.types.CalendarInterval

class ColumnarBatchSuite extends SparkFunSuite {

  private def allocate(capacity: Int, dt: DataType, memMode: MemoryMode): WritableColumnVector = {
    if (memMode == MemoryMode.OFF_HEAP) {
      new OffHeapColumnVector(capacity, dt)
    } else {
      new OnHeapColumnVector(capacity, dt)
    }
  }

  private def testVector(
      name: String,
      size: Int,
      dt: DataType)(
      block: (WritableColumnVector, MemoryMode) => Unit): Unit = {
    test(name) {
      Seq(MemoryMode.ON_HEAP, MemoryMode.OFF_HEAP).foreach { mode =>
        val vector = allocate(size, dt, mode)
        try block(vector, mode) finally {
          vector.close()
        }
      }
    }
  }

  testVector("Null APIs", 1024, IntegerType) {
    (column, memMode) =>
      val reference = mutable.ArrayBuffer.empty[Boolean]
      var idx = 0
      assert(!column.anyNullsSet())
      assert(column.numNulls() == 0)

      column.appendNotNull()
      reference += false
      assert(!column.anyNullsSet())
      assert(column.numNulls() == 0)

      column.appendNotNulls(3)
      (1 to 3).foreach(_ => reference += false)
      assert(!column.anyNullsSet())
      assert(column.numNulls() == 0)

      column.appendNull()
      reference += true
      assert(column.anyNullsSet())
      assert(column.numNulls() == 1)

      column.appendNulls(3)
      (1 to 3).foreach(_ => reference += true)
      assert(column.anyNullsSet())
      assert(column.numNulls() == 4)

      idx = column.elementsAppended

      column.putNotNull(idx)
      reference += false
      idx += 1
      assert(column.anyNullsSet())
      assert(column.numNulls() == 4)

      column.putNull(idx)
      reference += true
      idx += 1
      assert(column.anyNullsSet())
      assert(column.numNulls() == 5)

      column.putNulls(idx, 3)
      reference += true
      reference += true
      reference += true
      idx += 3
      assert(column.anyNullsSet())
      assert(column.numNulls() == 8)

      column.putNotNulls(idx, 4)
      reference += false
      reference += false
      reference += false
      reference += false
      idx += 4
      assert(column.anyNullsSet())
      assert(column.numNulls() == 8)

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.isNullAt(v._2))
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.nullsNativeAddress()
          assert(v._1 == (Platform.getByte(null, addr + v._2) == 1), "index=" + v._2)
        }
      }
  }

  testVector("Byte APIs", 1024, ByteType) {
    (column, memMode) =>
      val reference = mutable.ArrayBuffer.empty[Byte]

      var values = (10 :: 20 :: 30 :: 40 :: 50 :: Nil).map(_.toByte).toArray
      column.appendBytes(2, values, 0)
      reference += 10.toByte
      reference += 20.toByte

      column.appendBytes(3, values, 2)
      reference += 30.toByte
      reference += 40.toByte
      reference += 50.toByte

      column.appendBytes(6, 60.toByte)
      (1 to 6).foreach(_ => reference += 60.toByte)

      column.appendByte(70.toByte)
      reference += 70.toByte

      var idx = column.elementsAppended

      values = (1 :: 2 :: 3 :: 4 :: 5 :: Nil).map(_.toByte).toArray
      column.putBytes(idx, 2, values, 0)
      reference += 1
      reference += 2
      idx += 2

      column.putBytes(idx, 3, values, 2)
      reference += 3
      reference += 4
      reference += 5
      idx += 3

      column.putByte(idx, 9)
      reference += 9
      idx += 1

      column.putBytes(idx, 3, 4)
      reference += 4
      reference += 4
      reference += 4
      idx += 3

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getByte(v._2), "MemoryMode" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getByte(null, addr + v._2))
        }
      }
  }

  testVector("Short APIs", 1024, ShortType) {
    (column, memMode) =>
      val seed = System.currentTimeMillis()
      val random = new Random(seed)
      val reference = mutable.ArrayBuffer.empty[Short]

      var values = (10 :: 20 :: 30 :: 40 :: 50 :: Nil).map(_.toShort).toArray
      column.appendShorts(2, values, 0)
      reference += 10.toShort
      reference += 20.toShort

      column.appendShorts(3, values, 2)
      reference += 30.toShort
      reference += 40.toShort
      reference += 50.toShort

      column.appendShorts(6, 60.toShort)
      (1 to 6).foreach(_ => reference += 60.toShort)

      column.appendShort(70.toShort)
      reference += 70.toShort

      var idx = column.elementsAppended

      values = (1 :: 2 :: 3 :: 4 :: 5 :: Nil).map(_.toShort).toArray
      column.putShorts(idx, 2, values, 0)
      reference += 1
      reference += 2
      idx += 2

      column.putShorts(idx, 3, values, 2)
      reference += 3
      reference += 4
      reference += 5
      idx += 3

      column.putShort(idx, 9)
      reference += 9
      idx += 1

      column.putShorts(idx, 3, 4)
      reference += 4
      reference += 4
      reference += 4
      idx += 3

      while (idx < column.capacity) {
        val single = random.nextBoolean()
        if (single) {
          val v = random.nextInt().toShort
          column.putShort(idx, v)
          reference += v
          idx += 1
        } else {
          val n = math.min(random.nextInt(column.capacity / 20), column.capacity - idx)
          val v = (n + 1).toShort
          column.putShorts(idx, n, v)
          var i = 0
          while (i < n) {
            reference += v
            i += 1
          }
          idx += n
        }
      }

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getShort(v._2), "Seed = " + seed + " Mem Mode=" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getShort(null, addr + 2 * v._2))
        }
      }
  }

  testVector("Int APIs", 1024, IntegerType) {
    (column, memMode) =>
      val seed = System.currentTimeMillis()
      val random = new Random(seed)
      val reference = mutable.ArrayBuffer.empty[Int]

      var values = (10 :: 20 :: 30 :: 40 :: 50 :: Nil).toArray
      column.appendInts(2, values, 0)
      reference += 10
      reference += 20

      column.appendInts(3, values, 2)
      reference += 30
      reference += 40
      reference += 50

      column.appendInts(6, 60)
      (1 to 6).foreach(_ => reference += 60)

      column.appendInt(70)
      reference += 70

      var idx = column.elementsAppended

      values = (1 :: 2 :: 3 :: 4 :: 5 :: Nil).toArray
      column.putInts(idx, 2, values, 0)
      reference += 1
      reference += 2
      idx += 2

      column.putInts(idx, 3, values, 2)
      reference += 3
      reference += 4
      reference += 5
      idx += 3

      val littleEndian = new Array[Byte](8)
      littleEndian(0) = 7
      littleEndian(1) = 1
      littleEndian(4) = 6
      littleEndian(6) = 1

      column.putIntsLittleEndian(idx, 1, littleEndian, 4)
      column.putIntsLittleEndian(idx + 1, 1, littleEndian, 0)
      reference += 6 + (1 << 16)
      reference += 7 + (1 << 8)
      idx += 2

      column.putIntsLittleEndian(idx, 2, littleEndian, 0)
      reference += 7 + (1 << 8)
      reference += 6 + (1 << 16)
      idx += 2

      while (idx < column.capacity) {
        val single = random.nextBoolean()
        if (single) {
          val v = random.nextInt()
          column.putInt(idx, v)
          reference += v
          idx += 1
        } else {
          val n = math.min(random.nextInt(column.capacity / 20), column.capacity - idx)
          column.putInts(idx, n, n + 1)
          var i = 0
          while (i < n) {
            reference += (n + 1)
            i += 1
          }
          idx += n
        }
      }

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getInt(v._2), "Seed = " + seed + " Mem Mode=" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getInt(null, addr + 4 * v._2))
        }
      }
  }

  testVector("Long APIs", 1024, LongType) {
    (column, memMode) =>
      val seed = System.currentTimeMillis()
      val random = new Random(seed)
      val reference = mutable.ArrayBuffer.empty[Long]

      var values = (10L :: 20L :: 30L :: 40L :: 50L :: Nil).toArray
      column.appendLongs(2, values, 0)
      reference += 10L
      reference += 20L

      column.appendLongs(3, values, 2)
      reference += 30L
      reference += 40L
      reference += 50L

      column.appendLongs(6, 60L)
      (1 to 6).foreach(_ => reference += 60L)

      column.appendLong(70L)
      reference += 70L

      var idx = column.elementsAppended

      values = (1L :: 2L :: 3L :: 4L :: 5L :: Nil).toArray
      column.putLongs(idx, 2, values, 0)
      reference += 1
      reference += 2
      idx += 2

      column.putLongs(idx, 3, values, 2)
      reference += 3
      reference += 4
      reference += 5
      idx += 3

      val littleEndian = new Array[Byte](16)
      littleEndian(0) = 7
      littleEndian(1) = 1
      littleEndian(8) = 6
      littleEndian(10) = 1

      column.putLongsLittleEndian(idx, 1, littleEndian, 8)
      column.putLongsLittleEndian(idx + 1, 1, littleEndian, 0)
      reference += 6 + (1 << 16)
      reference += 7 + (1 << 8)
      idx += 2

      column.putLongsLittleEndian(idx, 2, littleEndian, 0)
      reference += 7 + (1 << 8)
      reference += 6 + (1 << 16)
      idx += 2

      while (idx < column.capacity) {
        val single = random.nextBoolean()
        if (single) {
          val v = random.nextLong()
          column.putLong(idx, v)
          reference += v
          idx += 1
        } else {

          val n = math.min(random.nextInt(column.capacity / 20), column.capacity - idx)
          column.putLongs(idx, n, n + 1)
          var i = 0
          while (i < n) {
            reference += (n + 1)
            i += 1
          }
          idx += n
        }
      }


      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getLong(v._2), "idx=" + v._2 +
          " Seed = " + seed + " MemMode=" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getLong(null, addr + 8 * v._2))
        }
      }
  }

  testVector("Float APIs", 1024, FloatType) {
    (column, memMode) =>
      val seed = System.currentTimeMillis()
      val random = new Random(seed)
      val reference = mutable.ArrayBuffer.empty[Float]

      var values = (.1f :: .2f :: .3f :: .4f :: .5f :: Nil).toArray
      column.appendFloats(2, values, 0)
      reference += .1f
      reference += .2f

      column.appendFloats(3, values, 2)
      reference += .3f
      reference += .4f
      reference += .5f

      column.appendFloats(6, .6f)
      (1 to 6).foreach(_ => reference += .6f)

      column.appendFloat(.7f)
      reference += .7f

      var idx = column.elementsAppended

      values = (1.0f :: 2.0f :: 3.0f :: 4.0f :: 5.0f :: Nil).toArray
      column.putFloats(idx, 2, values, 0)
      reference += 1.0f
      reference += 2.0f
      idx += 2

      column.putFloats(idx, 3, values, 2)
      reference += 3.0f
      reference += 4.0f
      reference += 5.0f
      idx += 3

      val buffer = new Array[Byte](8)
      Platform.putFloat(buffer, Platform.BYTE_ARRAY_OFFSET, 2.234f)
      Platform.putFloat(buffer, Platform.BYTE_ARRAY_OFFSET + 4, 1.123f)

      if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
        // Ensure array contains Little Endian floats
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        Platform.putFloat(buffer, Platform.BYTE_ARRAY_OFFSET, bb.getFloat(0))
        Platform.putFloat(buffer, Platform.BYTE_ARRAY_OFFSET + 4, bb.getFloat(4))
      }

      column.putFloats(idx, 1, buffer, 4)
      column.putFloats(idx + 1, 1, buffer, 0)
      reference += 1.123f
      reference += 2.234f
      idx += 2

      column.putFloats(idx, 2, buffer, 0)
      reference += 2.234f
      reference += 1.123f
      idx += 2

      while (idx < column.capacity) {
        val single = random.nextBoolean()
        if (single) {
          val v = random.nextFloat()
          column.putFloat(idx, v)
          reference += v
          idx += 1
        } else {
          val n = math.min(random.nextInt(column.capacity / 20), column.capacity - idx)
          val v = random.nextFloat()
          column.putFloats(idx, n, v)
          var i = 0
          while (i < n) {
            reference += v
            i += 1
          }
          idx += n
        }
      }

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getFloat(v._2), "Seed = " + seed + " MemMode=" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getFloat(null, addr + 4 * v._2))
        }
      }
  }

  testVector("Double APIs", 1024, DoubleType) {
    (column, memMode) =>
      val seed = System.currentTimeMillis()
      val random = new Random(seed)
      val reference = mutable.ArrayBuffer.empty[Double]

      var values = (.1 :: .2 :: .3 :: .4 :: .5 :: Nil).toArray
      column.appendDoubles(2, values, 0)
      reference += .1
      reference += .2

      column.appendDoubles(3, values, 2)
      reference += .3
      reference += .4
      reference += .5

      column.appendDoubles(6, .6)
      (1 to 6).foreach(_ => reference += .6)

      column.appendDouble(.7)
      reference += .7

      var idx = column.elementsAppended

      values = (1.0 :: 2.0 :: 3.0 :: 4.0 :: 5.0 :: Nil).toArray
      column.putDoubles(idx, 2, values, 0)
      reference += 1.0
      reference += 2.0
      idx += 2

      column.putDoubles(idx, 3, values, 2)
      reference += 3.0
      reference += 4.0
      reference += 5.0
      idx += 3

      val buffer = new Array[Byte](16)
      Platform.putDouble(buffer, Platform.BYTE_ARRAY_OFFSET, 2.234)
      Platform.putDouble(buffer, Platform.BYTE_ARRAY_OFFSET + 8, 1.123)

      if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
        // Ensure array contains Little Endian doubles
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        Platform.putDouble(buffer, Platform.BYTE_ARRAY_OFFSET, bb.getDouble(0))
        Platform.putDouble(buffer, Platform.BYTE_ARRAY_OFFSET + 8, bb.getDouble(8))
      }

      column.putDoubles(idx, 1, buffer, 8)
      column.putDoubles(idx + 1, 1, buffer, 0)
      reference += 1.123
      reference += 2.234
      idx += 2

      column.putDoubles(idx, 2, buffer, 0)
      reference += 2.234
      reference += 1.123
      idx += 2

      while (idx < column.capacity) {
        val single = random.nextBoolean()
        if (single) {
          val v = random.nextDouble()
          column.putDouble(idx, v)
          reference += v
          idx += 1
        } else {
          val n = math.min(random.nextInt(column.capacity / 20), column.capacity - idx)
          val v = random.nextDouble()
          column.putDoubles(idx, n, v)
          var i = 0
          while (i < n) {
            reference += v
            i += 1
          }
          idx += n
        }
      }

      reference.zipWithIndex.foreach { v =>
        assert(v._1 == column.getDouble(v._2), "Seed = " + seed + " MemMode=" + memMode)
        if (memMode == MemoryMode.OFF_HEAP) {
          val addr = column.valuesNativeAddress()
          assert(v._1 == Platform.getDouble(null, addr + 8 * v._2))
        }
      }
  }

  testVector("String APIs", 6, StringType) {
    (column, memMode) =>
      val reference = mutable.ArrayBuffer.empty[String]

      assert(column.arrayData().elementsAppended == 0)

      val str = "string"
      column.appendByteArray(str.getBytes(StandardCharsets.UTF_8),
        0, str.getBytes(StandardCharsets.UTF_8).length)
      reference += str
      assert(column.arrayData().elementsAppended == 6)

      var idx = column.elementsAppended

      val values = ("Hello" :: "abc" :: Nil).toArray
      column.putByteArray(idx, values(0).getBytes(StandardCharsets.UTF_8),
        0, values(0).getBytes(StandardCharsets.UTF_8).length)
      reference += values(0)
      idx += 1
      assert(column.arrayData().elementsAppended == 11)

      column.putByteArray(idx, values(1).getBytes(StandardCharsets.UTF_8),
        0, values(1).getBytes(StandardCharsets.UTF_8).length)
      reference += values(1)
      idx += 1
      assert(column.arrayData().elementsAppended == 14)

      // Just put llo
      val offset = column.putByteArray(idx, values(0).getBytes(StandardCharsets.UTF_8),
        2, values(0).getBytes(StandardCharsets.UTF_8).length - 2)
      reference += "llo"
      idx += 1
      assert(column.arrayData().elementsAppended == 17)

      // Put the same "ll" at offset. This should not allocate more memory in the column.
      column.putArray(idx, offset, 2)
      reference += "ll"
      idx += 1
      assert(column.arrayData().elementsAppended == 17)

      // Put a long string
      val s = "abcdefghijklmnopqrstuvwxyz"
      column.putByteArray(idx, (s + s).getBytes(StandardCharsets.UTF_8))
      reference += (s + s)
      idx += 1
      assert(column.arrayData().elementsAppended == 17 + (s + s).length)

      reference.zipWithIndex.foreach { v =>
        assert(v._1.length == column.getArrayLength(v._2), "MemoryMode=" + memMode)
        assert(v._1 == column.getUTF8String(v._2).toString,
          "MemoryMode" + memMode)
      }

      column.reset()
      assert(column.arrayData().elementsAppended == 0)
  }

  testVector("Int Array", 10, new ArrayType(IntegerType, true)) {
    (column, _) =>

      // Fill the underlying data with all the arrays back to back.
      val data = column.arrayData()
      var i = 0
      while (i < 6) {
        data.putInt(i, i)
        i += 1
      }

      // Populate it with arrays [0], [1, 2], [], [3, 4, 5]
      column.putArray(0, 0, 1)
      column.putArray(1, 1, 2)
      column.putArray(2, 2, 0)
      column.putArray(3, 3, 3)

      val a1 = ColumnVectorUtils.toPrimitiveJavaArray(column.getArray(0)).asInstanceOf[Array[Int]]
      val a2 = ColumnVectorUtils.toPrimitiveJavaArray(column.getArray(1)).asInstanceOf[Array[Int]]
      val a3 = ColumnVectorUtils.toPrimitiveJavaArray(column.getArray(2)).asInstanceOf[Array[Int]]
      val a4 = ColumnVectorUtils.toPrimitiveJavaArray(column.getArray(3)).asInstanceOf[Array[Int]]
      assert(a1 === Array(0))
      assert(a2 === Array(1, 2))
      assert(a3 === Array.empty[Int])
      assert(a4 === Array(3, 4, 5))

      // Verify the ArrayData APIs
      assert(column.getArray(0).length == 1)
      assert(column.getArray(0).getInt(0) == 0)

      assert(column.getArray(1).length == 2)
      assert(column.getArray(1).getInt(0) == 1)
      assert(column.getArray(1).getInt(1) == 2)

      assert(column.getArray(2).length == 0)

      assert(column.getArray(3).length == 3)
      assert(column.getArray(3).getInt(0) == 3)
      assert(column.getArray(3).getInt(1) == 4)
      assert(column.getArray(3).getInt(2) == 5)

      // Add a longer array which requires resizing
      column.reset()
      val array = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
      assert(data.capacity == 10)
      data.reserve(array.length)
      assert(data.capacity == array.length * 2)
      data.putInts(0, array.length, array, 0)
      column.putArray(0, 0, array.length)
      assert(ColumnVectorUtils.toPrimitiveJavaArray(column.getArray(0)).asInstanceOf[Array[Int]]
        === array)
  }

  test("toArray for primitive types") {
    (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode =>
      val len = 4

      val columnBool = allocate(len, new ArrayType(BooleanType, false), memMode)
      val boolArray = Array(false, true, false, true)
      boolArray.zipWithIndex.foreach { case (v, i) => columnBool.arrayData.putBoolean(i, v) }
      columnBool.putArray(0, 0, len)
      assert(columnBool.getArray(0).toBooleanArray === boolArray)
      columnBool.close()

      val columnByte = allocate(len, new ArrayType(ByteType, false), memMode)
      val byteArray = Array[Byte](0, 1, 2, 3)
      byteArray.zipWithIndex.foreach { case (v, i) => columnByte.arrayData.putByte(i, v) }
      columnByte.putArray(0, 0, len)
      assert(columnByte.getArray(0).toByteArray === byteArray)
      columnByte.close()

      val columnShort = allocate(len, new ArrayType(ShortType, false), memMode)
      val shortArray = Array[Short](0, 1, 2, 3)
      shortArray.zipWithIndex.foreach { case (v, i) => columnShort.arrayData.putShort(i, v) }
      columnShort.putArray(0, 0, len)
      assert(columnShort.getArray(0).toShortArray === shortArray)
      columnShort.close()

      val columnInt = allocate(len, new ArrayType(IntegerType, false), memMode)
      val intArray = Array(0, 1, 2, 3)
      intArray.zipWithIndex.foreach { case (v, i) => columnInt.arrayData.putInt(i, v) }
      columnInt.putArray(0, 0, len)
      assert(columnInt.getArray(0).toIntArray === intArray)
      columnInt.close()

      val columnLong = allocate(len, new ArrayType(LongType, false), memMode)
      val longArray = Array[Long](0, 1, 2, 3)
      longArray.zipWithIndex.foreach { case (v, i) => columnLong.arrayData.putLong(i, v) }
      columnLong.putArray(0, 0, len)
      assert(columnLong.getArray(0).toLongArray === longArray)
      columnLong.close()

      val columnFloat = allocate(len, new ArrayType(FloatType, false), memMode)
      val floatArray = Array(0.0F, 1.1F, 2.2F, 3.3F)
      floatArray.zipWithIndex.foreach { case (v, i) => columnFloat.arrayData.putFloat(i, v) }
      columnFloat.putArray(0, 0, len)
      assert(columnFloat.getArray(0).toFloatArray === floatArray)
      columnFloat.close()

      val columnDouble = allocate(len, new ArrayType(DoubleType, false), memMode)
      val doubleArray = Array(0.0, 1.1, 2.2, 3.3)
      doubleArray.zipWithIndex.foreach { case (v, i) => columnDouble.arrayData.putDouble(i, v) }
      columnDouble.putArray(0, 0, len)
      assert(columnDouble.getArray(0).toDoubleArray === doubleArray)
      columnDouble.close()
    }
  }

  testVector(
    "Struct Column",
    10,
    new StructType().add("int", IntegerType).add("double", DoubleType)) { (column, _) =>
      val c1 = column.getChildColumn(0)
      val c2 = column.getChildColumn(1)
      assert(c1.dataType() == IntegerType)
      assert(c2.dataType() == DoubleType)

      c1.putInt(0, 123)
      c2.putDouble(0, 3.45)
      c1.putInt(1, 456)
      c2.putDouble(1, 5.67)

      val s = column.getStruct(0)
      assert(s.columns()(0).getInt(0) == 123)
      assert(s.columns()(0).getInt(1) == 456)
      assert(s.columns()(1).getDouble(0) == 3.45)
      assert(s.columns()(1).getDouble(1) == 5.67)

      assert(s.getInt(0) == 123)
      assert(s.getDouble(1) == 3.45)

      val s2 = column.getStruct(1)
      assert(s2.getInt(0) == 456)
      assert(s2.getDouble(1) == 5.67)
  }

  testVector("Nest Array in Array", 10, new ArrayType(new ArrayType(IntegerType, true), true)) {
    (column, _) =>
      val childColumn = column.arrayData()
      val data = column.arrayData().arrayData()
      (0 until 6).foreach {
        case 3 => data.putNull(3)
        case i => data.putInt(i, i)
      }
      // Arrays in child column: [0], [1, 2], [], [null, 4, 5]
      childColumn.putArray(0, 0, 1)
      childColumn.putArray(1, 1, 2)
      childColumn.putArray(2, 2, 0)
      childColumn.putArray(3, 3, 3)
      // Arrays in column: [[0]], [[1, 2], []], [[], [null, 4, 5]], null
      column.putArray(0, 0, 1)
      column.putArray(1, 1, 2)
      column.putArray(2, 2, 2)
      column.putNull(3)

      assert(column.getArray(0).getArray(0).toIntArray() === Array(0))
      assert(column.getArray(1).getArray(0).toIntArray() === Array(1, 2))
      assert(column.getArray(1).getArray(1).toIntArray() === Array())
      assert(column.getArray(2).getArray(0).toIntArray() === Array())
      assert(column.getArray(2).getArray(1).isNullAt(0))
      assert(column.getArray(2).getArray(1).getInt(1) === 4)
      assert(column.getArray(2).getArray(1).getInt(2) === 5)
      assert(column.isNullAt(3))
  }

  private val structType: StructType = new StructType().add("i", IntegerType).add("l", LongType)

  testVector(
    "Nest Struct in Array",
    10,
    new ArrayType(structType, true)) { (column, _) =>
      val data = column.arrayData()
      val c0 = data.getChildColumn(0)
      val c1 = data.getChildColumn(1)
      // Structs in child column: (0, 0), (1, 10), (2, 20), (3, 30), (4, 40), (5, 50)
      (0 until 6).foreach { i =>
        c0.putInt(i, i)
        c1.putLong(i, i * 10)
      }
      // Arrays in column: [(0, 0), (1, 10)], [(1, 10), (2, 20), (3, 30)],
      // [(4, 40), (5, 50)]
      column.putArray(0, 0, 2)
      column.putArray(1, 1, 3)
      column.putArray(2, 4, 2)

      assert(column.getArray(0).getStruct(0, 2).toSeq(structType) === Seq(0, 0))
      assert(column.getArray(0).getStruct(1, 2).toSeq(structType) === Seq(1, 10))
      assert(column.getArray(1).getStruct(0, 2).toSeq(structType) === Seq(1, 10))
      assert(column.getArray(1).getStruct(1, 2).toSeq(structType) === Seq(2, 20))
      assert(column.getArray(1).getStruct(2, 2).toSeq(structType) === Seq(3, 30))
      assert(column.getArray(2).getStruct(0, 2).toSeq(structType) === Seq(4, 40))
      assert(column.getArray(2).getStruct(1, 2).toSeq(structType) === Seq(5, 50))
  }

  testVector(
    "Nest Array in Struct",
    10,
    new StructType()
      .add("int", IntegerType)
      .add("array", new ArrayType(IntegerType, true))) { (column, _) =>
      val c0 = column.getChildColumn(0)
      val c1 = column.getChildColumn(1)
      c0.putInt(0, 0)
      c0.putInt(1, 1)
      c0.putInt(2, 2)
      val c1Child = c1.arrayData()
      (0 until 6).foreach { i =>
        c1Child.putInt(i, i)
      }
      // Arrays in c1: [0, 1], [2], [3, 4, 5]
      c1.putArray(0, 0, 2)
      c1.putArray(1, 2, 1)
      c1.putArray(2, 3, 3)

      assert(column.getStruct(0).getInt(0) === 0)
      assert(column.getStruct(0).getArray(1).toIntArray() === Array(0, 1))
      assert(column.getStruct(1).getInt(0) === 1)
      assert(column.getStruct(1).getArray(1).toIntArray() === Array(2))
      assert(column.getStruct(2).getInt(0) === 2)
      assert(column.getStruct(2).getArray(1).toIntArray() === Array(3, 4, 5))
  }

  private val subSchema: StructType = new StructType()
    .add("int", IntegerType)
    .add("int", IntegerType)
  testVector(
    "Nest Struct in Struct",
    10,
    new StructType().add("int", IntegerType).add("struct", subSchema)) { (column, _) =>
      val c0 = column.getChildColumn(0)
      val c1 = column.getChildColumn(1)
      c0.putInt(0, 0)
      c0.putInt(1, 1)
      c0.putInt(2, 2)
      val c1c0 = c1.getChildColumn(0)
      val c1c1 = c1.getChildColumn(1)
      // Structs in c1: (7, 70), (8, 80), (9, 90)
      c1c0.putInt(0, 7)
      c1c0.putInt(1, 8)
      c1c0.putInt(2, 9)
      c1c1.putInt(0, 70)
      c1c1.putInt(1, 80)
      c1c1.putInt(2, 90)

      assert(column.getStruct(0).getInt(0) === 0)
      assert(column.getStruct(0).getStruct(1, 2).toSeq(subSchema) === Seq(7, 70))
      assert(column.getStruct(1).getInt(0) === 1)
      assert(column.getStruct(1).getStruct(1, 2).toSeq(subSchema) === Seq(8, 80))
      assert(column.getStruct(2).getInt(0) === 2)
      assert(column.getStruct(2).getStruct(1, 2).toSeq(subSchema) === Seq(9, 90))
  }

  test("ColumnarBatch basic") {
    (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode => {
      val schema = new StructType()
        .add("intCol", IntegerType)
        .add("doubleCol", DoubleType)
        .add("intCol2", IntegerType)
        .add("string", BinaryType)

      val capacity = ColumnarBatch.DEFAULT_BATCH_SIZE
      val columns = schema.fields.map { field =>
        allocate(capacity, field.dataType, memMode)
      }
      val batch = new ColumnarBatch(schema, columns.toArray, ColumnarBatch.DEFAULT_BATCH_SIZE)
      assert(batch.numCols() == 4)
      assert(batch.numRows() == 0)
      assert(batch.numValidRows() == 0)
      assert(batch.capacity() > 0)
      assert(batch.rowIterator().hasNext == false)

      // Add a row [1, 1.1, NULL]
      columns(0).putInt(0, 1)
      columns(1).putDouble(0, 1.1)
      columns(2).putNull(0)
      columns(3).putByteArray(0, "Hello".getBytes(StandardCharsets.UTF_8))
      batch.setNumRows(1)

      // Verify the results of the row.
      assert(batch.numCols() == 4)
      assert(batch.numRows() == 1)
      assert(batch.numValidRows() == 1)
      assert(batch.rowIterator().hasNext == true)
      assert(batch.rowIterator().hasNext == true)

      assert(columns(0).getInt(0) == 1)
      assert(columns(0).isNullAt(0) == false)
      assert(columns(1).getDouble(0) == 1.1)
      assert(columns(1).isNullAt(0) == false)
      assert(columns(2).isNullAt(0) == true)
      assert(columns(3).getUTF8String(0).toString == "Hello")

      // Verify the iterator works correctly.
      val it = batch.rowIterator()
      assert(it.hasNext())
      val row = it.next()
      assert(row.getInt(0) == 1)
      assert(row.isNullAt(0) == false)
      assert(row.getDouble(1) == 1.1)
      assert(row.isNullAt(1) == false)
      assert(row.isNullAt(2) == true)
      assert(columns(3).getUTF8String(0).toString == "Hello")
      assert(it.hasNext == false)
      assert(it.hasNext == false)

      // Filter out the row.
      row.markFiltered()
      assert(batch.numRows() == 1)
      assert(batch.numValidRows() == 0)
      assert(batch.rowIterator().hasNext == false)

      // Reset and add 3 rows
      batch.reset()
      assert(batch.numRows() == 0)
      assert(batch.numValidRows() == 0)
      assert(batch.rowIterator().hasNext == false)

      // Add rows [NULL, 2.2, 2, "abc"], [3, NULL, 3, ""], [4, 4.4, 4, "world]
      columns(0).putNull(0)
      columns(1).putDouble(0, 2.2)
      columns(2).putInt(0, 2)
      columns(3).putByteArray(0, "abc".getBytes(StandardCharsets.UTF_8))

      columns(0).putInt(1, 3)
      columns(1).putNull(1)
      columns(2).putInt(1, 3)
      columns(3).putByteArray(1, "".getBytes(StandardCharsets.UTF_8))

      columns(0).putInt(2, 4)
      columns(1).putDouble(2, 4.4)
      columns(2).putInt(2, 4)
      columns(3).putByteArray(2, "world".getBytes(StandardCharsets.UTF_8))
      batch.setNumRows(3)

      def rowEquals(x: InternalRow, y: Row): Unit = {
        assert(x.isNullAt(0) == y.isNullAt(0))
        if (!x.isNullAt(0)) assert(x.getInt(0) == y.getInt(0))

        assert(x.isNullAt(1) == y.isNullAt(1))
        if (!x.isNullAt(1)) assert(x.getDouble(1) == y.getDouble(1))

        assert(x.isNullAt(2) == y.isNullAt(2))
        if (!x.isNullAt(2)) assert(x.getInt(2) == y.getInt(2))

        assert(x.isNullAt(3) == y.isNullAt(3))
        if (!x.isNullAt(3)) assert(x.getString(3) == y.getString(3))
      }

      // Verify
      assert(batch.numRows() == 3)
      assert(batch.numValidRows() == 3)
      val it2 = batch.rowIterator()
      rowEquals(it2.next(), Row(null, 2.2, 2, "abc"))
      rowEquals(it2.next(), Row(3, null, 3, ""))
      rowEquals(it2.next(), Row(4, 4.4, 4, "world"))
      assert(!it.hasNext)

      // Filter out some rows and verify
      batch.markFiltered(1)
      assert(batch.numValidRows() == 2)
      val it3 = batch.rowIterator()
      rowEquals(it3.next(), Row(null, 2.2, 2, "abc"))
      rowEquals(it3.next(), Row(4, 4.4, 4, "world"))
      assert(!it.hasNext)

      batch.markFiltered(2)
      assert(batch.numValidRows() == 1)
      val it4 = batch.rowIterator()
      rowEquals(it4.next(), Row(null, 2.2, 2, "abc"))

      batch.close()
    }}
  }

  private def doubleEquals(d1: Double, d2: Double): Boolean = {
    if (d1.isNaN && d2.isNaN) {
      true
    } else {
      d1 == d2
    }
  }

  private def compareStruct(fields: Seq[StructField], r1: InternalRow, r2: Row, seed: Long) {
    fields.zipWithIndex.foreach { case (field: StructField, ordinal: Int) =>
      assert(r1.isNullAt(ordinal) == r2.isNullAt(ordinal), "Seed = " + seed)
      if (!r1.isNullAt(ordinal)) {
        field.dataType match {
          case BooleanType => assert(r1.getBoolean(ordinal) == r2.getBoolean(ordinal),
            "Seed = " + seed)
          case ByteType => assert(r1.getByte(ordinal) == r2.getByte(ordinal), "Seed = " + seed)
          case ShortType => assert(r1.getShort(ordinal) == r2.getShort(ordinal), "Seed = " + seed)
          case IntegerType => assert(r1.getInt(ordinal) == r2.getInt(ordinal), "Seed = " + seed)
          case LongType => assert(r1.getLong(ordinal) == r2.getLong(ordinal), "Seed = " + seed)
          case FloatType => assert(doubleEquals(r1.getFloat(ordinal), r2.getFloat(ordinal)),
            "Seed = " + seed)
          case DoubleType => assert(doubleEquals(r1.getDouble(ordinal), r2.getDouble(ordinal)),
            "Seed = " + seed)
          case t: DecimalType =>
            val d1 = r1.getDecimal(ordinal, t.precision, t.scale).toBigDecimal
            val d2 = r2.getDecimal(ordinal)
            assert(d1.compare(d2) == 0, "Seed = " + seed)
          case StringType =>
            assert(r1.getString(ordinal) == r2.getString(ordinal), "Seed = " + seed)
          case CalendarIntervalType =>
            assert(r1.getInterval(ordinal) === r2.get(ordinal).asInstanceOf[CalendarInterval])
          case ArrayType(childType, n) =>
            val a1 = r1.getArray(ordinal).array
            val a2 = r2.getList(ordinal).toArray
            assert(a1.length == a2.length, "Seed = " + seed)
            childType match {
              case DoubleType =>
                var i = 0
                while (i < a1.length) {
                  assert(doubleEquals(a1(i).asInstanceOf[Double], a2(i).asInstanceOf[Double]),
                    "Seed = " + seed)
                  i += 1
                }
              case FloatType =>
                var i = 0
                while (i < a1.length) {
                  assert(doubleEquals(a1(i).asInstanceOf[Float], a2(i).asInstanceOf[Float]),
                    "Seed = " + seed)
                  i += 1
                }
              case t: DecimalType =>
                var i = 0
                while (i < a1.length) {
                  assert((a1(i) == null) == (a2(i) == null), "Seed = " + seed)
                  if (a1(i) != null) {
                    val d1 = a1(i).asInstanceOf[Decimal].toBigDecimal
                    val d2 = a2(i).asInstanceOf[java.math.BigDecimal]
                    assert(d1.compare(d2) == 0, "Seed = " + seed)
                  }
                  i += 1
                }
              case _ => assert(a1 === a2, "Seed = " + seed)
            }
          case StructType(childFields) =>
            compareStruct(childFields, r1.getStruct(ordinal, fields.length),
              r2.getStruct(ordinal), seed)
          case _ =>
            throw new NotImplementedError("Not implemented " + field.dataType)
        }
      }
    }
  }

  test("Convert rows") {
    (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode => {
      val rows = Row(1, 2L, "a", 1.2, 'b'.toByte) :: Row(4, 5L, "cd", 2.3, 'a'.toByte) :: Nil
      val schema = new StructType()
        .add("i1", IntegerType)
        .add("l2", LongType)
        .add("string", StringType)
        .add("d", DoubleType)
        .add("b", ByteType)

      val batch = ColumnVectorUtils.toBatch(schema, memMode, rows.iterator.asJava)
      assert(batch.numRows() == 2)
      assert(batch.numCols() == 5)

      val it = batch.rowIterator()
      val referenceIt = rows.iterator
      while (it.hasNext) {
        compareStruct(schema, it.next(), referenceIt.next(), 0)
      }
      batch.close()
    }
    }}

  /**
   * This test generates a random schema data, serializes it to column batches and verifies the
   * results.
   */
  def testRandomRows(flatSchema: Boolean, numFields: Int) {
    // TODO: Figure out why StringType doesn't work on jenkins.
    val types = Array(
      BooleanType, ByteType, FloatType, DoubleType, IntegerType, LongType, ShortType,
      DecimalType.ShortDecimal, DecimalType.IntDecimal, DecimalType.ByteDecimal,
      DecimalType.FloatDecimal, DecimalType.LongDecimal, new DecimalType(5, 2),
      new DecimalType(12, 2), new DecimalType(30, 10), CalendarIntervalType)
    val seed = System.nanoTime()
    val NUM_ROWS = 200
    val NUM_ITERS = 1000
    val random = new Random(seed)
    var i = 0
    while (i < NUM_ITERS) {
      val schema = if (flatSchema) {
        RandomDataGenerator.randomSchema(random, numFields, types)
      } else {
        RandomDataGenerator.randomNestedSchema(random, numFields, types)
      }
      val rows = mutable.ArrayBuffer.empty[Row]
      var j = 0
      while (j < NUM_ROWS) {
        val row = RandomDataGenerator.randomRow(random, schema)
        rows += row
        j += 1
      }
      (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode => {
        val batch = ColumnVectorUtils.toBatch(schema, memMode, rows.iterator.asJava)
        assert(batch.numRows() == NUM_ROWS)

        val it = batch.rowIterator()
        val referenceIt = rows.iterator
        var k = 0
        while (it.hasNext) {
          compareStruct(schema, it.next(), referenceIt.next(), seed)
          k += 1
        }
        batch.close()
      }}
      i += 1
    }
  }

  test("Random flat schema") {
    testRandomRows(true, 15)
  }

  test("Random nested schema") {
    testRandomRows(false, 30)
  }

  test("null filtered columns") {
    val NUM_ROWS = 10
    val schema = new StructType()
      .add("key", IntegerType, nullable = false)
      .add("value", StringType, nullable = true)
    for (numNulls <- List(0, NUM_ROWS / 2, NUM_ROWS)) {
      val rows = mutable.ArrayBuffer.empty[Row]
      for (i <- 0 until NUM_ROWS) {
        val row = if (i < numNulls) Row.fromSeq(Seq(i, null)) else Row.fromSeq(Seq(i, i.toString))
        rows += row
      }
      (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode => {
        val batch = ColumnVectorUtils.toBatch(schema, memMode, rows.iterator.asJava)
        batch.filterNullsInColumn(1)
        batch.setNumRows(NUM_ROWS)
        assert(batch.numRows() == NUM_ROWS)
        val it = batch.rowIterator()
        // Top numNulls rows should be filtered
        var k = numNulls
        while (it.hasNext) {
          assert(it.next().getInt(0) == k)
          k += 1
        }
        assert(k == NUM_ROWS)
        batch.close()
      }}
    }
  }

  test("mutable ColumnarBatch rows") {
    val NUM_ITERS = 10
    val types = Array(
      BooleanType, FloatType, DoubleType, IntegerType, LongType, ShortType,
      DecimalType.ShortDecimal, DecimalType.IntDecimal, DecimalType.ByteDecimal,
      DecimalType.FloatDecimal, DecimalType.LongDecimal, new DecimalType(5, 2),
      new DecimalType(12, 2), new DecimalType(30, 10))
    for (i <- 0 to NUM_ITERS) {
      val random = new Random(System.nanoTime())
      val schema = RandomDataGenerator.randomSchema(random, numFields = 20, types)
      val oldRow = RandomDataGenerator.randomRow(random, schema)
      val newRow = RandomDataGenerator.randomRow(random, schema)

      (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode =>
        val batch = ColumnVectorUtils.toBatch(schema, memMode, (oldRow :: Nil).iterator.asJava)
        val columnarBatchRow = batch.getRow(0)
        newRow.toSeq.zipWithIndex.foreach(i => columnarBatchRow.update(i._2, i._1))
        compareStruct(schema, columnarBatchRow, newRow, 0)
        batch.close()
      }
    }
  }

  test("exceeding maximum capacity should throw an error") {
    (MemoryMode.ON_HEAP :: MemoryMode.OFF_HEAP :: Nil).foreach { memMode =>
      val column = allocate(1, ByteType, memMode)
      column.MAX_CAPACITY = 15
      column.appendBytes(5, 0.toByte)
      // Successfully allocate twice the requested capacity
      assert(column.capacity == 10)
      column.appendBytes(10, 0.toByte)
      // Allocated capacity doesn't exceed MAX_CAPACITY
      assert(column.capacity == 15)
      val ex = intercept[RuntimeException] {
        // Over-allocating beyond MAX_CAPACITY throws an exception
        column.appendBytes(10, 0.toByte)
      }
      assert(ex.getMessage.contains(s"Cannot reserve additional contiguous bytes in the " +
        s"vectorized reader"))
    }
  }

  test("create columnar batch from Arrow column vectors") {
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("int", 0, Long.MaxValue)
    val vector1 = ArrowUtils.toArrowField("int1", IntegerType, nullable = true)
      .createVector(allocator).asInstanceOf[NullableIntVector]
    vector1.allocateNew()
    val mutator1 = vector1.getMutator()
    val vector2 = ArrowUtils.toArrowField("int2", IntegerType, nullable = true)
      .createVector(allocator).asInstanceOf[NullableIntVector]
    vector2.allocateNew()
    val mutator2 = vector2.getMutator()

    (0 until 10).foreach { i =>
      mutator1.setSafe(i, i)
      mutator2.setSafe(i + 1, i)
    }
    mutator1.setNull(10)
    mutator1.setValueCount(11)
    mutator2.setNull(0)
    mutator2.setValueCount(11)

    val columnVectors = Seq(new ArrowColumnVector(vector1), new ArrowColumnVector(vector2))

    val schema = StructType(Seq(StructField("int1", IntegerType), StructField("int2", IntegerType)))
    val batch = new ColumnarBatch(schema, columnVectors.toArray[ColumnVector], 11)
    batch.setNumRows(11)

    assert(batch.numCols() == 2)
    assert(batch.numRows() == 11)

    val rowIter = batch.rowIterator().asScala
    rowIter.zipWithIndex.foreach { case (row, i) =>
      if (i == 10) {
        assert(row.isNullAt(0))
      } else {
        assert(row.getInt(0) == i)
      }
      if (i == 0) {
        assert(row.isNullAt(1))
      } else {
        assert(row.getInt(1) == i - 1)
      }
    }

    batch.close()
    allocator.close()
  }
}
