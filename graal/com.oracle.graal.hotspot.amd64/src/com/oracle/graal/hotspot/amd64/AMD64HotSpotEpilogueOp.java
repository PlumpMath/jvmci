/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.amd64;

import static com.oracle.graal.lir.LIRInstruction.OperandFlag.*;
import static com.oracle.jvmci.amd64.AMD64.*;
import static com.oracle.jvmci.code.ValueUtil.*;

import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.jvmci.code.*;
import com.oracle.jvmci.meta.*;

/**
 * Superclass for operations that use the value of RBP saved in a method's prologue.
 */
abstract class AMD64HotSpotEpilogueOp extends AMD64LIRInstruction {

    protected AMD64HotSpotEpilogueOp(LIRInstructionClass<? extends AMD64HotSpotEpilogueOp> c, AllocatableValue savedRbp) {
        super(c);
        this.savedRbp = savedRbp;
    }

    @Use({REG, STACK}) private AllocatableValue savedRbp;

    protected void leaveFrameAndRestoreRbp(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        if (isStackSlot(savedRbp)) {
            // Restoring RBP from the stack must be done before the frame is removed
            masm.movq(rbp, (AMD64Address) crb.asAddress(savedRbp));
        } else {
            Register framePointer = asRegister(savedRbp);
            if (!framePointer.equals(rbp)) {
                masm.movq(rbp, framePointer);
            }
        }
        crb.frameContext.leave(crb);
    }
}
