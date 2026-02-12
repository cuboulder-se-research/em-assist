package com.intellij.ml.llm.template.extractfunction

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

data class EFSuggestionList (
    @SerializedName("suggestion_list")
    val suggestionList: List<EFSuggestion>
)

@Serializable
data class EFSuggestion(
    @SerializedName("function_name")
    var functionName: String,

    @SerializedName("line_start")
    var lineStart: Int,

    @SerializedName("line_end")
    var lineEnd: Int
)
