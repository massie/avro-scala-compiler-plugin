package com.googlecode.avro
package plugin

import scala.tools._
import nsc.Global
import nsc.Phase
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import nsc.transform.Transform
import nsc.transform.InfoTransform
import nsc.transform.TypingTransformers
import nsc.symtab.Flags._
import nsc.util.Position
import nsc.util.NoPosition
import nsc.ast.TreeDSL
import nsc.typechecker
import scala.annotation.tailrec

import java.util.{Arrays => JArrays}

import scala.collection.JavaConversions._

import org.apache.avro.Schema

trait MethodGen extends ScalaAvroPluginComponent
                with    Transform
                with    TypingTransformers
                with    TreeDSL {
  import global._
  import definitions._
  	  
  val runsAfter = List[String]("objectgen")
  override val runsRightAfter = Some("objectgen")
  val phaseName = "methodgen"
  def newTransformer(unit: CompilationUnit) = new MethodGenTransformer(unit)    

  class MethodGenTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    import CODE._

    /** AST nodes for runtime helper methods */

    /** this.mkUtf8(this.sym) */
    private def string2utf8(clazz: Symbol, sym: Symbol): Tree =
      oneArgFunction(clazz, sym, "mkUtf8")

    /** this.mkByteBuffer(this.sym) */
    private def byteArray2byteBuffer(clazz: Symbol, sym: Symbol): Tree =
      oneArgFunction(clazz, sym, "mkByteBuffer")

    private def oneArgFunction(clazz: Symbol, sym: Symbol, funcName: String): Tree =
      Apply(
        This(clazz) DOT newTermName(funcName),
        List(This(clazz) DOT sym))

    /** this.sym.asInstanceOf[java.lang.Object] */
    private def sym2obj(clazz: Symbol, sym: Symbol): Tree = 
      This(clazz) DOT sym AS ObjectClass.tpe

    /** this.sym.toInt.asInstanceOf[java.lang.Object] */
    private def sym2int2obj(clazz: Symbol, sym: Symbol): Tree =
      This(clazz) DOT sym DOT newTermName("toInt") AS ObjectClass.tpe

    /** Map symbol to symbol handler */
    private var symMap = Map(
      StringClass -> (string2utf8 _),
      ArrayClass  -> ((clazz: Symbol, sym: Symbol) => 
        if (sym.tpe.typeArgs.head.typeSymbol == ByteClass)
          byteArray2byteBuffer(clazz,sym)
        else
          throw new UnsupportedOperationException("Cannot handle this right now")
        ),
      ShortClass  -> (sym2int2obj _), 
      ByteClass   -> (sym2int2obj _), 
      CharClass   -> (sym2int2obj _))

    private def construct(tpe: Type, args: List[Type]) = tpe match {
      case TypeRef(pre, sym, _) => TypeRef(pre, sym, args)
    }

    private def toAvroType(tpe: Type): Type = {
      if (tpe.typeSymbol.isSubClass(TraversableClass)) {
        // TODO: this is a bit fragile (for instance, what if somebody defines
        // their map as a type alias. there won't be any type params
        val size = tpe.typeArgs.size
        size match {
          case 1 =>
            construct(GenericArrayClass.tpe, List(toAvroType(tpe.typeArgs.head)))
          case 2 =>
            construct(JMapClass.tpe, List(utf8Class.tpe, toAvroType(tpe.typeArgs.tail.head)))
          case _ =>
            throw new UnsupportedOperationException("Unsupported type: " + tpe)
        }
      } else tpe.typeSymbol match {
        case OptionClass =>
          toAvroType(tpe.typeArgs.head)
        case StringClass => utf8Class.tpe 
        case ArrayClass  =>
          if (tpe.typeArgs.head.typeSymbol == ByteClass)
            byteBufferClass.tpe
          else throw new UnsupportedOperationException("Arrays not used for lists: use scala collections instead")
        case ShortClass  => IntClass.tpe
        case ByteClass   => IntClass.tpe
        case CharClass   => IntClass.tpe 
        case _ => tpe
      }
    }

    private def generateGetMethod(templ: Template, clazz: Symbol, instanceVars: List[Symbol]) = {
      val newSym = clazz.newMethod(clazz.pos.focus, newTermName("get"))
      newSym setFlag SYNTHETICMETH | OVERRIDE 
      newSym setInfo MethodType(newSym.newSyntheticValueParams(List(/*Boxed*/ IntClass.tpe)), /*Any*/ObjectClass.tpe)
      clazz.info.decls enter newSym 

      val arg = newSym ARG 0
      // TODO: throw the avro bad index exception here
      val default = List(DEFAULT ==> THROW(IndexOutOfBoundsExceptionClass, arg))
      val cases = for ((sym, i) <- instanceVars.zipWithIndex) yield {
        CASE(LIT(i)) ==> {
          //val fn = symMap get (sym.tpe.typeSymbol) getOrElse ((sym2obj _))
          //fn(clazz, sym)
          //TODO: inline the ones that are easy
          val avroTpe = toAvroType(sym.tpe)
          //println("toAvroType: " + avroTpe)
          val converted = 
            Apply(
              TypeApply(
                This(clazz) DOT newTermName("convert"),
                List(
                  TypeTree(sym.tpe),
                  TypeTree(avroTpe))),
                List(This(clazz) DOT sym)) 
          
          if (avroTpe.typeSymbol == GenericArrayClass) {
            val schema = 
              Apply(
                Apply(
                  This(clazz) DOT newTermName("getSchema") DOT newTermName("getField"),
                  List(LIT(sym.name.toString.trim))) DOT newTermName("schema"),
                Nil)
            val t = NEW(TypeTree(construct(GenericArrayWrapperClass.tpe, List(avroTpe.typeArgs.head))), schema, converted) AS ObjectClass.tpe
            //println("case tree: " + t)
            t
          } else {
            val t = converted AS ObjectClass.tpe
            //println("no wrapper tree: " + t)
            t
          }

        }
      }

      atOwner(clazz)(localTyper.typed {
        DEF(newSym) === {
          arg MATCH { cases ::: default : _* }
        }   
      })   
    }

    private def generateSetMethod(templ: Template, clazz: Symbol, instanceVars: List[Symbol]) = {
      val newSym = clazz.newMethod(clazz.pos.focus, newTermName("put"))
      newSym setFlag SYNTHETICMETH | OVERRIDE
      newSym setInfo MethodType(newSym.newSyntheticValueParams(List(IntClass.tpe, AnyClass.tpe)), UnitClass.tpe)
      clazz.info.decls enter newSym 

      // TODO: throw avro bad index class
      val default = List(DEFAULT ==> THROW(IndexOutOfBoundsExceptionClass, newSym ARG 0))

      val byteBufferTpe = byteBufferClass.tpe
      val utf8Tpe = utf8Class.tpe

      def selectSchemaField(sym: Symbol): Tree = 
        Apply(
          Select(
            Apply(
              This(clazz) DOT newTermName("getSchema") DOT newTermName("getField"),
              List(LIT(sym.name.toString.trim))),
            newTermName("schema")),
          Nil)

      val cases = for ((sym, i) <- instanceVars.zipWithIndex) yield {
        //val rhs = 
        //  // TODO: refactor this mess
        //  if (sym.tpe.typeSymbol == StringClass) {
        //    typer typed ((newSym ARG 1) AS utf8Tpe DOT newTermName("toString"))
        //  } else if (sym.tpe.typeSymbol == ArrayClass && sym.tpe.normalize.typeArgs.head == ByteClass.tpe) {
        //    typer typed ((newSym ARG 1) AS byteBufferTpe.normalize DOT newTermName("array"))
        //  } else if (sym.tpe.typeSymbol == ListClass) {
        //    val apply = 
        //      Apply(
        //        Select(
        //          This(clazz),
        //          newTermName("genericArrayToScalaList")),
        //        List(Apply(
        //          Select(
        //            This(clazz),
        //            newTermName("castToGenericArray")),
        //          List(newSym ARG 1)))) AS sym.tpe
        //    typer typed apply
        //  } else if (sym.tpe.typeSymbol == MapClass) {
        //    val apply = 
        //      Apply(
        //        Select(
        //          This(clazz),
        //          newTermName("jMapToScalaMap")),
        //        List(Apply(
        //          Select(
        //            This(clazz),
        //            newTermName("castToJMap")),
        //          List(newSym ARG 1)),
        //          selectSchemaField(sym))) AS sym.tpe
        //    typer typed apply
        //  } else if (sym.tpe.typeSymbol == OptionClass) {
        //    val paramSym = sym.tpe.typeArgs.head.typeSymbol
        //    val useNative = 
        //      (paramSym == utf8Class) ||
        //      (paramSym == byteBufferClass)
        //    val apply =
        //      Apply(
        //        This(clazz) DOT newTermName("wrapOption"),
        //        List(
        //          (newSym ARG 1),
        //          selectSchemaField(sym),
        //          LIT(useNative))) AS sym.tpe
        //    typer typed apply
        //  } else if (sym.tpe.typeSymbol == ShortClass) {
        //    typer typed ((newSym ARG 1) AS IntClass.tpe DOT newTermName("toShort"))
        //  } else if (sym.tpe.typeSymbol == ByteClass) {
        //    typer typed ((newSym ARG 1) AS IntClass.tpe DOT newTermName("toByte"))
        //  } else if (sym.tpe.typeSymbol == CharClass) {
        //    typer typed ((newSym ARG 1) AS IntClass.tpe DOT newTermName("toChar"))
        //  } else {
        //    typer typed ((newSym ARG 1) AS sym.tpe)
        //  }

        val avroTpe = toAvroType(sym.tpe)
        val rhs = Apply(
            TypeApply(
              This(clazz) DOT newTermName("convert"),
              List(
                TypeTree(avroTpe),
                TypeTree(sym.tpe))),
              List((newSym ARG 1) AS avroTpe))
        val target = Assign(This(clazz) DOT sym, rhs)
        CASE(LIT(i)) ==> target
      }

      atOwner(clazz)(localTyper.typed {
        DEF(newSym) === {
            (newSym ARG 0) MATCH { cases ::: default : _* }
        }   
      })
    }

    private def generateGetSchemaMethod(clazzTree: ClassDef): Tree = {
      val clazz = clazzTree.symbol
      val newSym = clazz.newMethod(clazz.pos.focus, newTermName("getSchema"))
      newSym setFlag SYNTHETICMETH | OVERRIDE
      newSym setInfo MethodType(newSym.newSyntheticValueParams(Nil), schemaClass.tpe)
      clazz.info.decls enter newSym 
      //println("localTyper.context1.enclClass: " + localTyper.context1.enclClass)
      //println("companionModuleOf(clazz): " + companionModuleOf(clazzTree))
      //println("companionModuleOf(clazz).moduleClass: " + companionModuleOf(clazzTree.symbol).moduleClass)

      val innerTree: Tree =  /** Not sure why compiler needs type information (Tree) here */
        if (clazz.owner.isClass) { /** Only when the owner of this class is another class can we perform
                                    *  the optimization of referencing the companion object */
            debug("--- clazz ---: " + clazz)
            debug("clazz.toplevelClass: " + clazz.toplevelClass)
            debug("clazz.outerClass: " + clazz.outerClass)
            debug("clazz.enclClass: " + clazz.enclClass)
            if (clazz.toplevelClass == clazz) {
              This(companionModuleOf(clazzTree.symbol).moduleClass) DOT newTermName("schema")
            } else {
              This(clazz.outerClass) DOT newTermName(clazz.name.toString) DOT newTermName("schema")
            }
        } else /** Fall back to the naive version in the other cases */
          Apply(
            Ident(newTermName("org")) DOT 
              newTermName("apache")   DOT
              newTermName("avro")     DOT
              newTermName("Schema")   DOT
              newTermName("parse"),
            List(LIT(retrieveRecordSchema(clazz).get.toString)))
      localTyper.typed {
        DEF(newSym) === { innerTree }
      }
    }

    private def generateGetUnionSchemaMethod(clazzTree: ClassDef, unionSchema: Schema): Tree = {
      val clazz = clazzTree.symbol
      val newSym = clazz.newMethod(clazz.pos.focus, newTermName("getSchema"))
      newSym setFlag SYNTHETICMETH | OVERRIDE
      newSym setInfo MethodType(newSym.newSyntheticValueParams(Nil), schemaClass.tpe)
      clazz.info.decls enter newSym 

      localTyper.typed {
        DEF(newSym) === { 
          Apply(
            Ident(newTermName("org")) DOT 
              newTermName("apache")   DOT
              newTermName("avro")     DOT
              newTermName("Schema")   DOT
              newTermName("parse"),
            List(LIT(unionSchema.toString)))
        }
      }
    }


    override def transform(tree: Tree) : Tree = {
      val newTree = tree match {
        case cd @ ClassDef(mods, name, tparams, impl) if (cd.symbol.tpe.parents.contains(avroUnionTrait.tpe)) =>
          //println("FOUND UNION: " + cd.symbol)
          val schema = getOrCreateUnionSchema(cd.symbol, 
              Schema.createUnion(JArrays.asList(retrieveUnionRecords(cd.symbol).map(s => retrieveRecordSchema(s).get).toArray:_*)))
          //println("SCHEMA: " + schema)
          cd.symbol.resetFlag(INTERFACE) /** force compiler to generate backing $class class */
          val newMethod = List(generateGetUnionSchemaMethod(cd, schema))
          val newImpl = treeCopy.Template(impl, impl.parents, impl.self, newMethod ::: impl.body)
          treeCopy.ClassDef(cd, mods, name, tparams, newImpl)
        case cd @ ClassDef(mods, name, tparams, impl) if (cd.symbol.tpe.parents.contains(avroRecordTrait.tpe)) =>
          debug(retrieveRecordSchema(cd.symbol))
          debug(cd.symbol.fullName + "'s enclClass: " + cd.symbol.enclClass)
          debug("owner.enclClass: " + cd.symbol.owner.enclClass)
          debug("toplevelClass: " + cd.symbol.toplevelClass)
          debug("owner.toplevelClass: " + cd.symbol.owner.toplevelClass)

          val instanceVars = for (member <- impl.body if isValDef(member)) yield { member.symbol }
          val newMethods = List(
            generateGetMethod(impl, cd.symbol, instanceVars),
            generateSetMethod(impl, cd.symbol, instanceVars),
            generateGetSchemaMethod(cd))

          val newImpl = treeCopy.Template(impl, impl.parents, impl.self, newMethods ::: impl.body)
          treeCopy.ClassDef(cd, mods, name, tparams, newImpl)
        case _ => tree
      }
      super.transform(newTree)
    }    
  }
}
