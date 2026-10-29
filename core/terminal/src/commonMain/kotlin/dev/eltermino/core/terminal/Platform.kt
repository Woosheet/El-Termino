package dev.eltermino.core.terminal

/** Picks the copy/paste shortcuts and whether Option may act as Meta. */
internal expect val isMacOs: Boolean

/**
 * Whether dragging with the mouse selects text, as on desktop; on a touch screen it takes a
 * long press, so that a drag can scroll.
 */
internal expect val selectsOnPress: Boolean
