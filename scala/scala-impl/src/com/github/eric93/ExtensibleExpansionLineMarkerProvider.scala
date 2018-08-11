package com.github.eric93

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.macros.expansion.MacroExpansionLineMarkerProvider

class ExtensibleExpansionLineMarkerProvider extends MacroExpansionLineMarkerProvider {
  val EP_NAME: ExtensionPointName[ExtensibleExpansionLineMarkerProvider] = ExtensionPointName.create("org.intellij.scala.expansionMarkerProvider")
  override protected def getExpandMarker(element: PsiElement): Option[Marker] = {
    val exts = EP_NAME.getExtensions()
    exts.length match {
      case 0 => None
      case 1 => exts(0).doExpandMarker(element)
      // case _ => ???
    }
  }
  override protected def getUndoMarker(element: PsiElement): Option[Marker] = {
    val exts = EP_NAME.getExtensions()
    exts.length match {
      case 0 => None
      case 1 => exts(0).doUndoMarker(element)
      // case _ => ???
    }
  }

  protected def doExpandMarker(element : PsiElement) : Option[Marker] = None
  protected def doUndoMarker(element : PsiElement) : Option[Marker] = None
}

