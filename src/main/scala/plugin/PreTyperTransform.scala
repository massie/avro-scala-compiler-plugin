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

trait PreTyperTransform extends ScalaAvroPluginComponent
                        with    Transform
                        with    TypingTransformers
                        with    TreeDSL {
  import global._
  import definitions._
  	  
  val runsAfter = List[String]("avronamer")
  override val runsRightAfter = Some("avronamer")
  val phaseName = "pretypertransform"
  def newTransformer(unit: CompilationUnit) = new PreTyperTransformTransformer(unit)    

  class PreTyperTransformTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    import CODE._

    private lazy val transformSynthetics = {
      val ret = doTransformSynthetics()
      debug("transformed %d synthetics".format(ret))
      ret
    }

    private def doTransformSynthetics() = {
      var transformed = 0
      for (syn <- unit.synthetics) {
        val (sym, tree) = syn

        // sym will be an object symbol, tree will be the ModuleDef tree
        if (sym.isModule && sym.companionClass.tpe.parents.contains(avroRecordTrait.tpe)) { 
          debug("Found synthetic module symbol: " + sym)
          tree match {
            case md @ ModuleDef(_, _, _) =>
              unit.synthetics(sym) = pimpModuleDef(md)
              transformed += 1
            case _ => 
              throw new AssertionError("Found module symbol not mapping to a module def tree: " + tree)
          }
        } else {
          debug("Ignoring synthetic module: " + sym + ", " + tree)
        }
      }

      transformed
    }

    private def pimpModuleDef(md: ModuleDef): Tree = {
      val impl  = md.impl
      val clazz = md.symbol.moduleClass

      val valSym = clazz.newValue(clazz.pos.focus, newTermName("schema "))
      valSym setFlag (PRIVATE | LOCAL)
      valSym setInfo schemaClass.tpe
      clazz.info.decls enter valSym

      val valDef = localTyper.typed {
        VAL(valSym) === {
          Apply(
            Ident(newTermName("org")) DOT 
              newTermName("apache")   DOT
              newTermName("avro")     DOT
              newTermName("Schema")   DOT
              newTermName("parse"),
            List(LIT("UNIMPLEMENTED")))
        }
      }

      // !!! HACK !!!
      val owner0 = localTyper.context1.enclClass.owner
      localTyper.context1.enclClass.owner = clazz

      val getterSym = clazz.newMethod(clazz.pos.focus, newTermName("schema"))
      getterSym setFlag (METHOD | STABLE | ACCESSOR)
      getterSym setInfo MethodType(getterSym.newSyntheticValueParams(Nil), schemaClass.tpe)
      clazz.info.decls enter getterSym

      val getDef = atOwner(clazz)(localTyper.typed {
        DEF(getterSym) === { THIS(clazz) DOT newTermName("schema ") }
      })

      // !!! RESTORE HACK !!!
      localTyper.context1.enclClass.owner = owner0

      //debug("clazz.info.decls: %s".format(clazz.info.decls))

      //val valSym = clazz.newValue(clazz.pos.focus, newTermName("testSchema"))
      //valSym setInfo NoType
      //clazz.info.decls enter valSym

      //val applyTree = 
      //  Apply(
      //    Ident(newTermName("org")) DOT 
      //      newTermName("apache")   DOT
      //      newTermName("avro")     DOT
      //      newTermName("Schema")   DOT
      //      newTermName("parse"),
      //    List(LIT("UNIMPLEMENTED"))) /* Filled in by a later phase */
      //val valTree = ValDef(Modifiers(valSym.flags), sym.name, TypeTree(), applyTree) setSymbol NoSymbol //valSym

      //val valTree = ValDef(NoMods, newTermName("testSchema"), TypeTree(), applyTree) setSymbol NoSymbol //valSym
      val impl0 = treeCopy.Template(impl, impl.parents, impl.self, valDef :: getDef :: impl.body)
      treeCopy.ModuleDef(md, md.mods, md.name, impl0)
    }

    override def transform(tree: Tree) : Tree = {
      transformSynthetics /* Invoke each time, but its a lazy val so only invoked once */
      val newTree = tree match {
        case md @ ModuleDef(mods, name, impl) 
          if (md.symbol.companionClass != NoSymbol && 
              md.symbol.companionClass.tpe.parents.contains(avroRecordTrait.tpe)) =>

          debug("got moduledef to pimp: " + md.symbol)

          //val clazz = md.symbol.moduleClass
          //debug("clazz.info.decls: %s".format(clazz.info.decls))

          //for (decl <- clazz.info.decls) {
          //  debug("decl: %s, declClass: %s".format(decl, decl.getClass.getName))
          //  //debug("decl.tpe: %s".format(decl.tpe))
          //  //debug("decl.flags: " + decl.flags)
          //}

          //for (stmt <- impl.body) {
          //  stmt match {
          //    case ValDef(mods, name, tpe, rhs) =>
          //      debug("ValDef found with name: " + name)

          //      val sym = stmt.symbol
          //      debug("sym: %s, symClass: %s".format(sym, sym.getClass.getName))

          //      val info = stmt.symbol.info
          //      debug("info: %s, infoClass: %s".format(info, info.getClass.getName))

          //      debug("----")

          //    case _ =>
          //      debug("Other statement found: " + stmt)
          //  }
          //}

          pimpModuleDef(md)
        //case md @ ModuleDef(mods, name, impl) =>
        //  debug("Ignoring module def: " + md.symbol + ", " + md)
        //  debug("companion: " + md.symbol.companionClass)
        //  tree
        case _ => tree
      }
      super.transform(newTree)
    }
  }
}
