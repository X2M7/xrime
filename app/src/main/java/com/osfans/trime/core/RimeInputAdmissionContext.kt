/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core

/** Propagates accepted input lineage across dispatcher switches without retaining content. */
internal object RimeInputAdmissionContext {
    val current = ThreadLocal<Long?>()
}
