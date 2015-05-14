# SBT Project Definition #

Use the following SBT project template
```
import sbt._
import xsbt.ScalaInstance

import java.io.File

class YourProject(info: ProjectInfo) extends DefaultProject(info) with AutoCompilerPlugins
{
  val radlabRepo = "Radlab Repository" at "http://scads.knowsql.org/nexus/content/groups/public/"
  val avroScala = compilerPlugin("com.googlecode" % "avro-scala-compiler-plugin" % "1.1-SNAPSHOT")
  val pluginRuntime = "com.googlecode" % "avro-scala-compiler-plugin" % "1.1-SNAPSHOT"
  val avro = "org.apache.hadoop" % "avro" % "1.3.3"

  private val pluginDeps = Set("avro-1.3.3.jar", "jackson-core-asl-1.4.2.jar", "jackson-mapper-asl-1.4.2.jar")

  override def getScalaInstance(version: String) = { 
    val pluginJars = compileClasspath.filter(path => pluginDeps.contains(path.name)).getFiles.toSeq
    withExtraJars(super.getScalaInstance(version), pluginJars) 
  }
  
  def withExtraJars(si: ScalaInstance, extra: Seq[File]) =
    ScalaInstance(si.version, si.libraryJar, si.compilerJar, info.launcher, extra : _*)
}
```