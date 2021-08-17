package mfwgenerics.kotq.window

import mfwgenerics.kotq.expr.Expr

sealed interface FrameRangeMarker<out T>

object Unbounded: FrameRangeMarker<Nothing>
object CurrentRow: FrameRangeMarker<Nothing>

class Preceding<T : Any>(
    val offset: Expr<T>
): FrameRangeMarker<Expr<T>>

class Following<T : Any>(
    val offset: Expr<T>
): FrameRangeMarker<Expr<T>>
