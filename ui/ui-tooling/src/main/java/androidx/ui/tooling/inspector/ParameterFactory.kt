/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.ui.tooling.inspector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.InspectableParameter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.ui.tooling.inspector.ParameterType.DimensionDp
import kotlin.math.abs

/**
 * Factory of [NodeParameter]s.
 *
 * Each parameter value is converted to a user readable value.
 */
internal class ParameterFactory {
    /**
     * A map from known values to a user readable string representation.
     */
    private val valueLookup = mutableMapOf<Any, String>()
    private var creatorCache: ParameterCreator? = null

    var density = Density(1.0f)

    init {
        loadFromCompanion(AbsoluteAlignment.Companion)
        loadFromCompanion(Alignment.Companion)
        loadFromInterface(Arrangement::class.java)
        loadFromCompanion(FontFamily.Companion)
        loadFromCompanion(FontWeight.Companion, ignore = "getW")
        loadFromCompanion(Shadow.Companion)
        loadFromCompanion(TextDecoration.Companion)
        loadFromCompanion(TextIndent.Companion)
        valueLookup[Color.Unset] = "Unset"
    }

    /**
     * Create a [NodeParameter] from the specified parameter [name] and [value].
     *
     * Attempt to convert the value to a user readable value.
     * For now: return null when a conversion is not possible/found.
     */
    fun create(node: MutableInspectorNode, name: String, value: Any?): NodeParameter? {
        val creator = creatorCache ?: ParameterCreator()
        try {
            return creator.create(node, name, value)
        } finally {
            creatorCache = creator
        }
    }

    private fun loadFromInterface(interfaceClass: Class<*>) {
        // REDO: If we decide to add a kotlin reflection dependency
        interfaceClass.declaredFields
            .filter { it.name != "INSTANCE" }
            .associateByTo(valueLookup, { it.isAccessible = true; it[null]!! }, { it.name })
    }

    private fun loadFromCompanion(companionInstance: Any, ignore: String? = null) {
        // REDO: If we decide to add a kotlin reflection dependency
        companionInstance::class.java.declaredMethods.asSequence()
            .filter {
                java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                        it.returnType != Void.TYPE &&
                        it.parameterTypes.isEmpty() &&
                        it.name.startsWith("get") &&
                        (ignore == null || !it.name.startsWith(ignore))
            }
            .associateByTo(valueLookup, { it(companionInstance)!! }, { it.name.substring(3) })
    }

    private inner class ParameterCreator {
        private var node: MutableInspectorNode? = null

        fun create(node: MutableInspectorNode, name: String, value: Any?): NodeParameter? =
            try {
                this.node = node
                create(name, value)
            } finally {
                this.node = null
            }

        private fun create(name: String, value: Any?): NodeParameter? {
            if (value == null) {
                return null
            }
            val text = valueLookup[value]
            if (text != null) {
                return NodeParameter(name, ParameterType.String, text)
            }
            return when (value) {
                is AnnotatedString -> NodeParameter(name, ParameterType.String, value.text)
                is BaselineShift -> createFromBaselineShift(name, value)
                is Boolean -> NodeParameter(name, ParameterType.Boolean, value)
                is BorderStroke -> createFromBorder(name, value)
                is Brush -> createFromBrush(name, value)
                is Color -> NodeParameter(name, ParameterType.Color, value.toArgb())
                is CornerBasedShape -> createFromCornerBasedShape(name, value)
                is CornerSize -> createFromCornerSize(name, value)
                is Double -> NodeParameter(name, ParameterType.Double, value)
                is Dp -> NodeParameter(name, DimensionDp, value.value)
                is Enum<*> -> NodeParameter(name, ParameterType.String, value.toString())
                is Float -> NodeParameter(name, ParameterType.Float, value)
                is FontListFontFamily -> createFromFontListFamily(name, value)
                is FontWeight -> NodeParameter(name, ParameterType.Int32, value.weight)
                is PaddingValues -> createFromPaddingValues(name, value)
                is Modifier -> createFromModifier(name, value)
                is InspectableParameter -> createFromInspectableParameter(name, value)
                is Int -> NodeParameter(name, ParameterType.Int32, value)
                is Locale -> NodeParameter(name, ParameterType.String, value.toString())
                is LocaleList ->
                    NodeParameter(name, ParameterType.String, value.localeList.joinToString())
                is Long -> NodeParameter(name, ParameterType.Int64, value)
                is Offset -> createFromOffset(name, value)
                is Shadow -> createFromShadow(name, value)
                is Shape -> NodeParameter(name, ParameterType.String, Shape::class.java.simpleName)
                is String -> NodeParameter(name, ParameterType.String, value)
                is TextGeometricTransform -> createFromTextGeometricTransform(name, value)
                is TextIndent -> createFromTextIndent(name, value)
                is TextStyle -> createFromTextStyle(name, value)
                is TextUnit -> createFromTextUnit(name, value)
                else -> null
            }
        }

        private fun createFromBaselineShift(name: String, value: BaselineShift): NodeParameter {
            val converted = when (value.multiplier) {
                BaselineShift.None.multiplier -> "None"
                BaselineShift.Subscript.multiplier -> "Subscript"
                BaselineShift.Superscript.multiplier -> "Superscript"
                else -> return NodeParameter(name, ParameterType.Float, value.multiplier)
            }
            return NodeParameter(name, ParameterType.String, converted)
        }

        private fun createFromBorder(name: String, value: BorderStroke): NodeParameter {
            val parameter = NodeParameter(name, ParameterType.String, "BorderStroke")
            val elements = parameter.elements
            create("width", value.width)?.let { elements.add(it) }
            create("brush", value.brush)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromBrush(name: String, value: Brush): NodeParameter =
            when (value) {
                is SolidColor -> NodeParameter(name, ParameterType.Color, value.value.toArgb())
                else -> NodeParameter(
                    name,
                    ParameterType.String,
                    classNameOf(value, Brush::class.java)
                )
            }

        private fun createFromCornerBasedShape(
            name: String,
            value: CornerBasedShape
        ): NodeParameter? {
            val parameter = NodeParameter(
                name, ParameterType.String,
                classNameOf(value, CornerBasedShape::class.java)
            )
            val elements = parameter.elements
            create("topLeft", value.topLeft)?.let { elements.add(it) }
            create("topRight", value.topRight)?.let { elements.add(it) }
            create("bottomLeft", value.bottomLeft)?.let { elements.add(it) }
            create("bottomRight", value.bottomRight)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromCornerSize(name: String, value: CornerSize): NodeParameter {
            val size = Size(node!!.width.toFloat(), node!!.height.toFloat())
            val pixels = value.toPx(size, density)
            return NodeParameter(name, DimensionDp, with(density) { pixels.toDp().value })
        }

        // For now: select ResourceFontFont closest to W400 and Normal, and return the resId
        private fun createFromFontListFamily(
            name: String,
            value: FontListFontFamily
        ): NodeParameter? =
            findBestResourceFont(value)?.let {
                NodeParameter(name, ParameterType.Resource, it.resId)
            }

        private fun createFromPaddingValues(name: String, value: PaddingValues): NodeParameter {
            val parameter = NodeParameter(name, ParameterType.String, "PaddingValues")
            val elements = parameter.elements
            create("start", value.start)?.let { elements.add(it) }
            create("end", value.end)?.let { elements.add(it) }
            create("top", value.top)?.let { elements.add(it) }
            create("bottom", value.bottom)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromInspectableParameter(
            name: String,
            value: InspectableParameter
        ): NodeParameter {
            val tempValue = value.valueOverride ?: ""
            val parameterName = name.ifEmpty { value.nameFallback } ?: "element"
            val parameterValue = if (tempValue is InspectableParameter) "" else tempValue
            val parameter = create(parameterName, parameterValue)
                ?: NodeParameter(parameterName, ParameterType.String, "")
            val elements = parameter.elements
            value.inspectableElements.mapNotNullTo(elements) { create(it.name, it.value) }
            return parameter
        }

        private fun createFromModifier(name: String, value: Modifier): NodeParameter? =
            when {
                name.isNotEmpty() -> {
                    val parameter = NodeParameter(name, ParameterType.String, "")
                    val elements = parameter.elements
                    value.foldIn(elements) { acc, m ->
                        create("", m)?.let { param -> acc.apply { add(param) } } ?: acc
                    }
                    parameter
                }
                value is InspectableParameter -> createFromInspectableParameter(name, value)
                else -> null
            }

        private fun createFromOffset(name: String, value: Offset): NodeParameter {
            val parameter = NodeParameter(name, ParameterType.String, Offset::class.java.simpleName)
            val elements = parameter.elements
            elements.add(NodeParameter("x", DimensionDp, with(density) { value.x.toDp().value }))
            elements.add(NodeParameter("y", DimensionDp, with(density) { value.y.toDp().value }))
            return parameter
        }

        private fun createFromShadow(name: String, value: Shadow): NodeParameter {
            val parameter = NodeParameter(name, ParameterType.String, Shadow::class.java.simpleName)
            val elements = parameter.elements
            val blurRadius = with(density) { value.blurRadius.toDp().value }
            create("color", value.color)?.let { elements.add(it) }
            create("offset", value.offset)?.let { elements.add(it) }
            elements.add(NodeParameter("blurRadius", DimensionDp, blurRadius))
            return parameter
        }

        private fun createFromTextGeometricTransform(
            name: String,
            value: TextGeometricTransform
        ): NodeParameter {
            val parameter = NodeParameter(
                name, ParameterType.String,
                TextGeometricTransform::class.java.simpleName
            )
            val elements = parameter.elements
            create("scaleX", value.scaleX)?.let { elements.add(it) }
            create("skewX", value.skewX)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromTextIndent(name: String, value: TextIndent): NodeParameter {
            val parameter =
                NodeParameter(name, ParameterType.String, TextIndent::class.java.simpleName)
            val elements = parameter.elements
            create("firstLine", value.firstLine)?.let { elements.add(it) }
            create("restLine", value.restLine)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromTextStyle(name: String, value: TextStyle): NodeParameter {
            val parameter =
                NodeParameter(name, ParameterType.String, TextStyle::class.java.simpleName)
            val elements = parameter.elements
            create("color", value.color)?.let { elements.add(it) }
            create("fontSize", value.fontSize)?.let { elements.add(it) }
            create("fontWeight", value.fontWeight)?.let { elements.add(it) }
            create("fontStyle", value.fontStyle)?.let { elements.add(it) }
            create("fontSynthesis", value.fontSynthesis)?.let { elements.add(it) }
            create("fontFamily", value.fontFamily)?.let { elements.add(it) }
            create("fontFeatureSettings", value.fontFeatureSettings)?.let { elements.add(it) }
            create("letterSpacing", value.letterSpacing)?.let { elements.add(it) }
            create("baselineShift", value.baselineShift)?.let { elements.add(it) }
            create("textGeometricTransform", value.textGeometricTransform)
                ?.let { elements.add(it) }
            create("localeList", value.localeList)?.let { elements.add(it) }
            create("background", value.background)?.let { elements.add(it) }
            create("textDecoration", value.textDecoration)?.let { elements.add(it) }
            create("shadow", value.shadow)?.let { elements.add(it) }
            create("textAlign", value.textAlign)?.let { elements.add(it) }
            create("textDirection", value.textDirection)?.let { elements.add(it) }
            create("lineHeight", value.lineHeight)?.let { elements.add(it) }
            create("textIndent", value.textIndent)?.let { elements.add(it) }
            return parameter
        }

        private fun createFromTextUnit(name: String, value: TextUnit): NodeParameter? =
            when (value.type) {
                TextUnitType.Sp -> NodeParameter(name, ParameterType.DimensionSp, value.value)
                TextUnitType.Em -> NodeParameter(name, ParameterType.DimensionEm, value.value)
                TextUnitType.Inherit -> NodeParameter(name, ParameterType.String, "Inherit")
            }

        private fun classNameOf(value: Any, default: Class<*>): String =
            value.javaClass.simpleName.ifEmpty { default.simpleName }

        /**
         * Select a resource font among the font in the family to represent the font
         *
         * Prefer the font closest to [FontWeight.Normal] and [FontStyle.Normal]
         */
        private fun findBestResourceFont(value: FontListFontFamily): ResourceFont? =
            value.fonts.asSequence().filterIsInstance<ResourceFont>().minByOrNull {
                abs(it.weight.weight - FontWeight.Normal.weight) + it.style.ordinal
            }
    }
}
