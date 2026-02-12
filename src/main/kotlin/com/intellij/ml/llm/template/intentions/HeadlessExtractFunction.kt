package com.intellij.ml.llm.template.intentions

import com.intellij.ml.llm.template.extractfunction.EFCandidate
import com.intellij.ml.llm.template.utils.CodeTransformer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class HeadlessExtractFunction: ApplyExtractFunctionTransformationIntention() {

    val candidates: MutableList<EFCandidate> = mutableListOf()
    var completed: Boolean = false
    override fun getInstruction(project: Project, editor: Editor): String? {
        return "headless em-assist"
    }

    override fun getText(): String {
        return "Headless em assist."
    }

    override fun showExtractFunctionPopup(
        project: Project,
        editor: Editor,
        file: PsiFile,
        candidates: List<EFCandidate>,
        codeTransformer: CodeTransformer
    ){
        this.candidates.addAll(candidates)
        completed = true
    }

}