package com.googlecode.avro
package plugin

import scala.tools.nsc
import nsc.util._
import nsc.Global
import nsc.Phase
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import nsc.transform.Transform
import nsc.transform.TypingTransformers
import nsc.typechecker.Analyzer
import nsc.typechecker.Duplicators
import nsc.symtab.Flags._
import nsc.ast.TreeDSL

trait AvroAnalyzer extends Analyzer with TreeDSL with ScalaAvroPluginLogger {
  import global._

  //override def newTyper(context: Context) = new SpecialTyper(context)

  lazy val avroRecordTrait = definitions.getClass("com.googlecode.avro.marker.AvroRecord")

  class SpecialTyper(context: Context) extends Typer(context) {
    //override def typedModuleDef(mdef: ModuleDef): Tree = {
    //  debug("typedModuleDef: " + mdef)
    //  import CODE._
    //  val linkedClass = mdef.symbol.companionClass
    //  debug("linkedClass: " + linkedClass)
    //  val mdef0 = 
    //    if (linkedClass != NoSymbol) {

    //      debug("linkedClass.tpe.parents: %s".format(linkedClass.tpe.parents.mkString(",")))
    //      if (linkedClass.tpe.parents.contains(avroRecordTrait.tpe)) {
    //        debug("creating schema field for module: " + mdef)
    //        val applyTree = 
    //          Apply(
    //            Ident(newTermName("org")) DOT 
    //              newTermName("apache")   DOT
    //              newTermName("avro")     DOT
    //              newTermName("Schema")   DOT
    //              newTermName("parse"),
    //            List(LIT("UNIMPLEMENTED"))) /* Filled in by a later phase */
    //        val schema = ValDef(NoMods, newTermName("schema"), TypeTree(), applyTree)
    //        val impl = mdef.impl
    //        val impl0 = treeCopy.Template(impl, impl.parents, impl.self, List(schema) ::: impl.body)
    //        treeCopy.ModuleDef(mdef, mdef.mods, mdef.name, impl0)
    //      } else mdef
    //    } else mdef
    //  super.typedModuleDef(mdef0)
    //}

    //override def typed(tree: Tree, mode: Int, pt: Type): Tree = tree match {
    //  case Select(qual, name) if ((qual.symbol ne null) && name.toString == "schema") =>
    //    debug("typed for select: " + tree)
    //    val compClass = qual.symbol.companionClass
    //    debug("qual.symbol.companionClass: %s".format(compClass))
    //    if ((compClass ne null) && compClass != NoSymbol) {
    //      debug("compClass.tpe.parents: %s".format(compClass.tpe.parents))
    //      val isSubtype = compClass.tpe.parents.contains(avroRecordTrait.tpe)
    //      if (isSubtype) {
    //        debug("Special case-ing for field schema from companion object of AvroRecord for select tree: " + qual)
    //        val schemaClass = definitions.getClass("org.apache.avro.Schema")
    //        tree setType PolyType(Nil, schemaClass.tpe)
    //      } else super.typed(tree, mode, pt)
    //    } else super.typed(tree, mode, pt)
    //  case _ => super.typed(tree, mode, pt)
    //}

    //override def member(qual: Tree, name: Name): Symbol = {
    //  println("member called with qual %s, name %s".format(qual, name))
    //  println("qual.symbol: %s".format(qual.symbol))
    //  if (qual.symbol ne null) {
    //    val compClass = qual.symbol.companionClass
    //    println("qual.symbol.companionClass: %s".format(compClass))
    //    if ((compClass ne null) && compClass != NoSymbol) {
    //      println("compClass.tpe.parents: %s".format(compClass.tpe.parents))
    //      val avroRecordTrait = definitions.getClass("com.googlecode.avro.marker.AvroRecord")
    //      val subtype = compClass.tpe.parents.contains(avroRecordTrait.tpe)
    //      println("isSubtype? " + subtype)
    //      if (subtype) {
    //        if (name.toString == "schema") {
    //          println("Special case-ing for field schema from companion object of AvroRecord")

    //          val clazz = qual.symbol.moduleClass
    //          val sym = clazz.newMethod(newTermName("schema"))
    //          sym resetFlag (METHOD)
    //          sym setFlag (ACCESSOR)

    //          val schemaClass = definitions.getClass("org.apache.avro.Schema")
    //          sym setInfo PolyType(Nil, schemaClass.tpe)

    //          println("sym: %s, class: %s".format(sym.toString, sym.getClass.getName))
    //          return sym
    //        } else {
    //          println("expecting `schema`, but got `%s`".format(name.toString))
    //        }
    //      }
    //    }
    //  }
    //  val ret0 = super.member(qual, name)
    //  println("ret0: %s, classOf: %s, ret0.info: %s, ret0.info.getClass.getName: %s".format(ret0.toString, ret0.getClass.getName, ret0.info, ret0.info.getClass.getName))
    //  ret0
    //}
  }
}
