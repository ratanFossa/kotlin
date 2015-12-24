/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.jvm.internal

private object DoubleCompanionObject {
    val MIN_VALUE: Double = java.lang.Double.MIN_VALUE
    val MAX_VALUE: Double = java.lang.Double.MAX_VALUE
    val POSITIVE_INFINITY : Double = java.lang.Double.POSITIVE_INFINITY
    val NEGATIVE_INFINITY : Double = java.lang.Double.NEGATIVE_INFINITY
    val NaN : Double = java.lang.Double.NaN
}

private object FloatCompanionObject {
    val MIN_VALUE: Float = java.lang.Float.MIN_VALUE
    val MAX_VALUE: Float = java.lang.Float.MAX_VALUE
    val POSITIVE_INFINITY : Float = java.lang.Float.POSITIVE_INFINITY
    val NEGATIVE_INFINITY : Float = java.lang.Float.NEGATIVE_INFINITY
    val NaN : Float = java.lang.Float.NaN
}

private object IntCompanionObject {
    const val MIN_VALUE: Int = java.lang.Integer.MIN_VALUE
    const val MAX_VALUE: Int = java.lang.Integer.MAX_VALUE
}

private object LongCompanionObject {
    const val MIN_VALUE: Long = java.lang.Long.MIN_VALUE
    const val MAX_VALUE: Long = java.lang.Long.MAX_VALUE
}

private object ShortCompanionObject {
    const val MIN_VALUE: Short = java.lang.Short.MIN_VALUE
    const val MAX_VALUE: Short = java.lang.Short.MAX_VALUE
}

private object ByteCompanionObject {
    const val MIN_VALUE: Byte = java.lang.Byte.MIN_VALUE
    const val MAX_VALUE: Byte = java.lang.Byte.MAX_VALUE
}


private object CharCompanionObject {}

private object StringCompanionObject {}
private object EnumCompanionObject {}