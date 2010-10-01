/*
 * Copyright (c) 2009-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product that is
 * described in this document. In particular, and without limitation, these intellectual property rights may include one
 * or more of the U.S. patents listed at http://www.sun.com/patents and one or more additional patents or pending patent
 * applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun Microsystems, Inc. standard
 * license agreement and applicable provisions of the FAR and its supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or registered
 * trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks are used under license and
 * are trademarks or registered trademarks of SPARC International, Inc. in the U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open Company, Ltd.
 */
package com.sun.hotspot.c1x;

import com.sun.c1x.target.amd64.AMD64;
import com.sun.c1x.util.Util;
import com.sun.cri.ci.CiCallingConvention;
import com.sun.cri.ci.CiKind;
import com.sun.cri.ci.CiRegister;
import com.sun.cri.ci.CiStackSlot;
import com.sun.cri.ci.CiTarget;
import com.sun.cri.ci.CiValue;
import com.sun.cri.ri.RiRegisterConfig;

/**
 * @author Thomas Wuerthinger
 *
 */
public class HotSpotRegisterConfig implements RiRegisterConfig {

    // be careful - the contents of this array are duplicated in c1x_CodeInstaller.cpp
    @Override
    public CiRegister[] getAllocatableRegisters() {
        return new CiRegister[] { AMD64.rax, AMD64.rbx, AMD64.rcx, AMD64.rdx, AMD64.rsi, AMD64.rdi, AMD64.r8, AMD64.r9, /* AMD64.r10, */AMD64.r11, AMD64.r12, AMD64.r13, AMD64.r14, AMD64.xmm0, AMD64.xmm1, AMD64.xmm2,
                        AMD64.xmm3, AMD64.xmm4, AMD64.xmm5, AMD64.xmm6, AMD64.xmm7, AMD64.xmm8, AMD64.xmm9, AMD64.xmm10, AMD64.xmm11, AMD64.xmm12, AMD64.xmm13, AMD64.xmm14, AMD64.xmm15};
    }

    private final CiRegister[] generalParameterRegisters;
    private final CiRegister[] xmmParameterRegisters = new CiRegister[] { AMD64.xmm0, AMD64.xmm1, AMD64.xmm2, AMD64.xmm3, AMD64.xmm4, AMD64.xmm5, AMD64.xmm6, AMD64.xmm7};

    public HotSpotRegisterConfig(HotSpotVMConfig config) {
        if (config.windowsOs) {
            generalParameterRegisters = new CiRegister[] { AMD64.rdx, AMD64.r8, AMD64.r9, AMD64.rdi, AMD64.rsi, AMD64.rcx};
        } else {
            generalParameterRegisters = new CiRegister[] { AMD64.rsi, AMD64.rdx, AMD64.rcx, AMD64.r8, AMD64.r9, AMD64.rdi};
        }
    }

    @Override
    public int getCalleeSaveRegisterOffset(CiRegister register) {
        return 0;
    }

    @Override
    public CiRegister[] getCallerSaveRegisters() {
        return getAllocatableRegisters();
    }

    @Override
    public CiRegister getFramePointerRegister() {
        return AMD64.rbp;
    }

    @Override
    public CiRegister getIntegerRegister(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CiCallingConvention getJavaCallingConvention(CiKind[] parameters, boolean outgoing, CiTarget target) {
        return callingConvention(parameters, outgoing, target);
    }

    private CiCallingConvention callingConvention(CiKind[] types, boolean outgoing, CiTarget target) {
        CiValue[] locations = new CiValue[types.length];

        int currentGeneral = 0;
        int currentXMM = 0;
        int currentStackIndex = 0;

        for (int i = 0; i < types.length; i++) {
            final CiKind kind = types[i];

            switch (kind) {
                case Byte:
                case Boolean:
                case Short:
                case Char:
                case Int:
                case Long:
                case Word:
                case Object:
                    if (currentGeneral < generalParameterRegisters.length) {
                        CiRegister register = generalParameterRegisters[currentGeneral++];
                        locations[i] = register.asValue(kind);
                    }
                    break;
                case Float:
                case Double:
                    if (currentXMM < xmmParameterRegisters.length) {
                        CiRegister register = xmmParameterRegisters[currentXMM++];
                        locations[i] = register.asValue(kind);
                    }
                    break;
                default:
                    throw Util.shouldNotReachHere();
            }

            if (locations[i] == null) {
                // we need to adjust for the frame pointer stored on the stack, which shifts incoming arguments by one slot
                locations[i] = CiStackSlot.get(kind.stackKind(), currentStackIndex + (outgoing ? 0 : 1), !outgoing);
                currentStackIndex += target.spillSlots(kind);
            }
        }

        return new CiCallingConvention(locations, currentStackIndex * target.spillSlotSize);
    }

    @Override
    public int getMinimumCalleeSaveFrameSize() {
        return 0;
    }

    @Override
    public CiCallingConvention getNativeCallingConvention(CiKind[] parameters, boolean outgoing, CiTarget target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CiRegister[] getRegisterReferenceMapOrder() {
        return getAllocatableRegisters();
    }

    @Override
    public CiRegister getReturnRegister(CiKind kind) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
            case Long:
            case Object:
            case Word:
                return AMD64.rax;
            case Float:
            case Double:
                return AMD64.xmm0;
            case Void:
            case Illegal:
                return null;
            default:
                throw new UnsupportedOperationException("no return register for type " + kind);
        }
    }

    @Override
    public CiCallingConvention getRuntimeCallingConvention(CiKind[] parameters, CiTarget target) {
        return callingConvention(parameters, true, target);
    }

    @Override
    public CiRegister getScratchRegister() {
        return AMD64.r10;
    }

    @Override
    public CiRegister getStackPointerRegister() {
        return AMD64.rsp;
    }

}
