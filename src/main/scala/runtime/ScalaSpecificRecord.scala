package com.googlecode.avro
package runtime

import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.io.BinaryEncoder
import org.apache.avro.specific.{SpecificDatumWriter, SpecificRecord}
import org.apache.avro.generic.{GenericArray, GenericData}
import org.apache.avro.util.Utf8

import scala.collection.mutable.{Map => MMap, ListBuffer}
import scala.collection.immutable.{Map => ImmMap}
import scala.reflect.Manifest

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.{Map => JMap, HashMap => JHashMap}

private[runtime] class UnimplementedFunctionalityException extends RuntimeException("Unimplemented")

trait ScalaSpecificRecord extends SpecificRecord {
  def toBytes: Array[Byte] = {
    val out = new ByteArrayOutputStream
    val enc = new BinaryEncoder(out)
    val writer = new SpecificDatumWriter[ScalaSpecificRecord](getSchema)
    writer.write(this, enc)
    out.toByteArray
  }
}

trait AvroConversions {

  def mkUtf8(p: String): Utf8 = 
    if (p eq null)
      null
    else
      new Utf8(p)

  def mkByteBuffer(bytes: Array[Byte]): ByteBuffer = 
    if (bytes eq null)
      null
    else
      ByteBuffer.wrap(bytes)

  def scalaMapToJMap(map: Map[_,_], schema: Schema): JMap[_,_] = {
    if (schema eq null)
      throw new IllegalArgumentException("schema must not be null")
    if (map eq null)
      return null.asInstanceOf[JMap[_,_]]
      
    val jHashMap = new JHashMap[Any,Any]
    map.foreach(kv => {
      val (key, value0) = kv
      val value = schema.getValueType.getType match {
        case Type.ARRAY => 
          scalaListToGenericArray(value0.asInstanceOf[List[_]], schema.getValueType)          
        case Type.MAP =>
          scalaMapToJMap(value0.asInstanceOf[Map[_,_]], schema.getValueType)
        case Type.STRING =>
          // assume String -> Utf8 for now
          new Utf8(value0.asInstanceOf[String])
        case Type.BYTES =>
          // assume Array[Byte] -> ByteBuffer for now
          ByteBuffer.wrap(value0.asInstanceOf[Array[Byte]])
        case _ => value0
      }
      // assume that key is String
      jHashMap.put(new Utf8(key.toString), value)
    })
    jHashMap
  }

  def jMapToScalaMap(jmap: JMap[_,_], schema: Schema): Map[_,_] = {
    if (schema eq null)
      throw new IllegalArgumentException("schema must not be null")
    if (jmap eq null)
      return null.asInstanceOf[Map[_,_]]

    val mMap = MMap[Any,Any]()
    val iter = jmap.keySet.iterator
    while (iter.hasNext) {
      val key = iter.next
      val value0 = jmap.get(key)
      val value = schema.getValueType.getType match {
        case Type.ARRAY =>
          genericArrayToScalaList(value0.asInstanceOf[GenericArray[_]])
        case Type.MAP =>
          jMapToScalaMap(value0.asInstanceOf[JMap[_,_]], schema.getValueType)
        case Type.STRING =>
          // assume Utf8 -> String for now
          value0.asInstanceOf[Utf8].toString
        case Type.BYTES =>
          // assume ByteBuffer -> Array[Byte] for now
          value0.asInstanceOf[ByteBuffer].array
        case _ => value0
      }
      // assume that key is Utf8
      mMap += key.asInstanceOf[Utf8].toString -> value
    }
    ImmMap[Any,Any](mMap.toList:_*)
  }

  /** NOTE: 
    * For the runtime, to get a list of byte buffers, you MUST do
    * List[ByteBuffer]. To get a list of strings you MUST do
    * List[String]. List[Array[Byte]] and List[Utf8] will NOT work. 
    * TODO: we'll have to resolve this! basically these two methods will
    * need to take a paramater which indicates how to resolve these issues.
    */

  /**
    * We can't do type safe lists here because the types can change based on runtime types.
    * For example: 
    * 1) List[Array[Byte]] -> GenericArray[ByteBuffer]
    * 2) List[List[Foo]] -> GenericArray[GenericArray[Foo]]
    */
  def scalaListToGenericArray(list: List[_], schema: Schema): GenericArray[_] = {
      if (list == null) return null
      val genericArray = new GenericData.Array[Any](10, schema)
      if (schema.getType != Type.ARRAY)
          throw new IllegalArgumentException("Not array type schema")        
      schema.getElementType.getType match {
          case Type.ARRAY => list.foreach( 
              elem => genericArray.add(scalaListToGenericArray(elem.asInstanceOf[List[_]], schema.getElementType)) )
          case Type.BYTES => list.foreach( 
              elem => 
                  //if (elem.isInstanceOf[Array[Byte]])
                  //    genericArray.add(ByteBuffer.wrap(elem.asInstanceOf[Array[Byte]])) 
                  //else
                      genericArray.add(elem.asInstanceOf[ByteBuffer])
              )
          case Type.STRING => list.foreach(
              elem => 
                  //if (elem.isInstanceOf[String])
                      genericArray.add(new Utf8(elem.asInstanceOf[String])) 
                  //else
                  //    genericArray.add(elem.asInstanceOf[Utf8])
              )
          case _ => list.foreach( elem => genericArray.add(elem) )
      }
      genericArray
  }

  def genericArrayToScalaList(genericArray: GenericArray[_]): List[_] = {
      if (genericArray eq null)
        return null
      val listBuffer = new ListBuffer[Any]
      val iter = genericArray.iterator
      while (iter.hasNext) {
          val next = iter.next
          if (next.isInstanceOf[GenericArray[_]])
              listBuffer += genericArrayToScalaList(next.asInstanceOf[GenericArray[_]])
          else if (next.isInstanceOf[ByteBuffer])
              listBuffer += next.asInstanceOf[ByteBuffer].array
          else if (next.isInstanceOf[Utf8]) 
              listBuffer += next.asInstanceOf[Utf8].toString
          else 
              listBuffer += next
      }
      listBuffer.toList
  }

  def castToGenericArray(obj: Any): GenericArray[_] = obj.asInstanceOf[GenericArray[_]]

  def castToJMap(obj: Any): JMap[_,_] = obj.asInstanceOf[JMap[_,_]]

  /** Assume that schema is a union type which contains only
   2 fields, a NULL field, and another field that isn't NULL 
   */                                    
  def findNonNull(schema: Schema): Schema = schema.getType match {
    case Type.UNION =>
      if (schema.getTypes.size != 2) {
        throw new IllegalArgumentException("Not a union schema with 2 types")
      }
      val left = schema.getTypes.get(0)
      val right = schema.getTypes.get(1)
      if (left.getType == Type.NULL) {
        right
      } else if (right.getType == Type.NULL) {
        left
      } else {
        throw new IllegalArgumentException("No null type found")
      }
    case _ => 
      throw new IllegalArgumentException("Not a union schema")
  }

  def unwrapOption(opt: Option[_], schema: Schema): AnyRef = opt match {
    case Some(inner) =>
      // NOTE: we don't have to worry about another nested union schema, since avro
      // disallowes nested unions
      val schema0 = findNonNull(schema)

      // must do conversions here if necessary...
      schema0.getType match {
        case Type.STRING =>
          if (inner.isInstanceOf[Utf8])
            inner.asInstanceOf[Utf8]
          else
            new Utf8(inner.toString)
        case Type.ARRAY =>
          // TODO: handle the obj being a native generic array
          scalaListToGenericArray(inner.asInstanceOf[List[_]], schema0)      
        case Type.BYTES =>
          if (inner.isInstanceOf[ByteBuffer])
            inner.asInstanceOf[ByteBuffer]
          else
            ByteBuffer.wrap(inner.asInstanceOf[Array[Byte]])
        case _ =>
          inner.asInstanceOf[AnyRef]
      }
    case None | null =>
      null.asInstanceOf[AnyRef]
  }
   
  def wrapOption(obj: Any, schema: Schema, returnNativeType: Boolean): Option[_] = {
    if (obj.asInstanceOf[AnyRef] eq null)
      None
    else {
      val schema0 = findNonNull(schema)
      val obj0 = if (returnNativeType) obj else schema0.getType match {
        case Type.STRING => 
          obj.toString
        case Type.ARRAY =>
          // TODO: handle generic 
          genericArrayToScalaList(obj.asInstanceOf[GenericArray[_]])           
        case Type.BYTES =>
          obj.asInstanceOf[ByteBuffer].array          
        case _ =>
          obj
      }
      Some(obj0)
    }
  }

}
