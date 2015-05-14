# Background #
We are attempting to build a Scala compiler plugin to auto-generate [Avro](http://avro.apache.org/) serializable classes based on some simple `case class` definitions. Our goal is to provide more compile time type safety than the Java classes offer, and make the use of Avro records in Scala much easier than in Java. This plugin is for the Scala 2.8 compiler, and for the Avro 1.3.3 runtime.

# Getting Started #
Please see either MavenUsage or SbtUsage for instructions on how to compile your own project using the plugin.

# Usage #
Let us define a simple message class. Normally in Avro, you would write a JSON
file which looks like this:
```
{                                                                              
"namespace" : "json",
"protocol"  : "Json",
"types" : [
  { 
    "name" : "Get", 
    "type" : "record", 
    "fields" : [
      { "name" : "key", "type" : "string" }
    ]
  },
  { 
    "name" : "Put", 
    "type" : "record", 
    "fields" : [
      { "name" : "key", "type" : "string" },
      { "name" : "value", "type" : "bytes" }
    ]
  },
  { 
    "name" : "Request", 
    "type" : "record", 
    "fields" : [
      { "name" : "from", "type" : "string" },
      { "name" : "actions", 
        "type" : {
          "type" : "array",
          "items" : ["Get", "Put"]
        }
      }
    ]
  }
]
}
```
In which case, the Avro compiler will generate `Get.java`, `Put.java`, and `Request.java` for you to use in your application (which you can then use in your Scala application).

With our plugin, you can instead write Scala case classes which accomplish the same task, but look a lot cleaner:
```
package plugin

import com.googlecode.avro.marker._

sealed trait RequestAction extends AvroUnion

case class Get(var key: String)
  extends RequestAction 
  with    AvroRecord

case class Put(var key: String, var value: Array[Byte])
  extends RequestAction
  with    AvroRecord

case class Request(var from: String, var actions: List[RequestAction])
  extends AvroRecord
```

That's all you need to do! Our compiler plugin will automatically generate the necessary methods that make your case classes Avro serializable.

Now you can use the classes as such:
```
scala> import plugin._
import plugin._

scala> val request = Request("stephentu", List(Get("bob"), Put("bill", "contents".getBytes)))
request: plugin.Request = {"from": "stephentu", "actions": [{"key": "bob"}, {"key": "bill", "value": {"bytes": "contents"}}]}

scala> request.toBytes // AvroRecord instances contain additional helper methods
res0: Array[Byte] = Array(18, 115, 116, 101, 112, 104, ...

scala> val request0 = (new Request).parse(request.toBytes)
request0: plugin.Request = {"from": "stephentu", "actions": [{"key": "bob"}, {"key": "bill", "value": {"bytes": "contents"}}]}

scala> request0 eq request // a new instance
res1: Boolean = false
```

Notice above how unions were handled in the Scala version. They are defined by using a `sealed trait` which inherits from `AvroUnion`. This ensures us type safety at compile time, which the Java generated classes do not provide:
```
scala> val request = Request("stephentu", List("Reject Me"))
<console>:8: error: type mismatch;
 found   : java.lang.String("Reject Me")
 required: plugin.RequestAction
       val request = Request("stephentu", List("Reject Me"))
```
Cool, this error was caught at compile time! Now let's see what happens when we try to do the same thing with the Java record classes. I have defined equivalent versions under the `json` package:
```
scala> import json.{Request => JRequest}
import json.{Request=>JRequest}

scala> val jrequest = new JRequest
jrequest: json.Request = {"from": null, "actions": null}

scala> jrequest.from = new Utf8("stephentu")

scala> val arraySchema = jrequest.getSchema.getField("actions").schema
arraySchema: org.apache.avro.Schema = ...

scala> val actions = new GenericData.Array[Object](16, arraySchema)
actions: org.apache.avro.generic.GenericData.Array[java.lang.Object] = []

scala> actions.add("Reject me")

scala> jrequest.actions = actions // oops

scala> val baos = new ByteArrayOutputStream
baos: java.io.ByteArrayOutputStream = 

scala> val enc = new SpecificDatumWriter[JRequest](jrequest.getSchema)
enc: org.apache.avro.specific.SpecificDatumWriter[json.Request] = org.apache.avro.specific.SpecificDatumWriter@121bfd6

scala> enc.write(jrequest, new BinaryEncoder(baos))                   
org.apache.avro.AvroRuntimeException: Not in union ...: Reject me
	at org.apache.avro.generic.GenericData.resolveUnion(GenericData.java:340)
	at org.apache.avro.generic.GenericDatumWriter.write(GenericDatumWriter.java:67)
	at org.apache.avro.generic.GenericDatumWriter.writeArray(GenericDatumWriter.java:117)
	at org.apache.avro.generic.GenericDatumWriter.write(GenericDatumWriter.java:64)
	at org.apache.avro.generic.GenericDatumWriter.writeRecord(GenericDatumWriter.java:89)
	at org.apache.avro.generic.GenericDatumWriter.write(GenericDatumWriter.java:62)
	at org.apache.avro.generic.GenericDatumWrit...
```
No dice here, error detected at runtime.

# Performance #
Detailed performance results can be found on the PerformanceDetails page

# Null Safe Option Pattern #
In addition to typesafe unions, we also offer (read: require) the use of `Option[T]` to specify nullable fields.
```
scala> case class Rec0(var nonNullableString: String) extends AvroRecord
defined class Rec0

scala> Rec0.schema
res0: org.apache.avro.Schema = {"type":"record","name":"Rec0","namespace":"$iw","fields":[{"name":"nonNullableString","type":"string"}]}

scala> case class Rec(var nullableString: Option[String]) extends AvroRecord
defined class Rec

scala> Rec.schema
res1: org.apache.avro.Schema = {"type":"record","name":"Rec","namespace":"$iw","fields":[{"name":"nullableString","type":["null","string"]}]}

scala> Rec(None).toBytes
res12: Array[Byte] = Array(0)

scala> Rec(Some("Hello")).toBytes
res13: Array[Byte] = Array(2, 10, 72, 101, 108, 108, 111)
```

# Known Limitations #
  * The plugin only enhances Scala `case class` classes. This is done on purpose, since `case class` semantics are the closest to Avro record semantics.
  * Currently, we are unable to support immutable records. This is on purpose, since we want records generated to be interoperable with Avro's readers. Unfortunately, since Avro records are mutable and rely on mutability for construction (via the `put` method), we  require all fields to be `vars`. We are working to address this issue.
  * Several Avro features are punted on. These include enumerations and fixed record fields.
  * Records can only have at most 22 fields in the primary constructor. This is a limitation of case classes. You can get around this by adding fields to the body of the record.