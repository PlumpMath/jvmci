/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jvmci.hotspot.amd64;

import static jdk.internal.jvmci.amd64.AMD64.r12;
import static jdk.internal.jvmci.amd64.AMD64.r15;
import static jdk.internal.jvmci.amd64.AMD64.r8;
import static jdk.internal.jvmci.amd64.AMD64.r9;
import static jdk.internal.jvmci.amd64.AMD64.rax;
import static jdk.internal.jvmci.amd64.AMD64.rcx;
import static jdk.internal.jvmci.amd64.AMD64.rdi;
import static jdk.internal.jvmci.amd64.AMD64.rdx;
import static jdk.internal.jvmci.amd64.AMD64.rsi;
import static jdk.internal.jvmci.amd64.AMD64.rsp;
import static jdk.internal.jvmci.amd64.AMD64.xmm0;
import static jdk.internal.jvmci.amd64.AMD64.xmm1;
import static jdk.internal.jvmci.amd64.AMD64.xmm2;
import static jdk.internal.jvmci.amd64.AMD64.xmm3;
import static jdk.internal.jvmci.amd64.AMD64.xmm4;
import static jdk.internal.jvmci.amd64.AMD64.xmm5;
import static jdk.internal.jvmci.amd64.AMD64.xmm6;
import static jdk.internal.jvmci.amd64.AMD64.xmm7;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jdk.internal.jvmci.code.Architecture;
import jdk.internal.jvmci.code.CallingConvention;
import jdk.internal.jvmci.code.CallingConvention.Type;
import jdk.internal.jvmci.code.Register;
import jdk.internal.jvmci.code.RegisterAttributes;
import jdk.internal.jvmci.code.RegisterConfig;
import jdk.internal.jvmci.code.StackSlot;
import jdk.internal.jvmci.code.TargetDescription;
import jdk.internal.jvmci.common.JVMCIError;
import jdk.internal.jvmci.hotspot.HotSpotVMConfig;
import jdk.internal.jvmci.meta.AllocatableValue;
import jdk.internal.jvmci.meta.JavaKind;
import jdk.internal.jvmci.meta.JavaType;
import jdk.internal.jvmci.meta.LIRKind;
import jdk.internal.jvmci.meta.PlatformKind;
import jdk.internal.jvmci.meta.Value;

public class AMD64HotSpotRegisterConfig implements RegisterConfig {

    private final Architecture architecture;

    private final Register[] allocatable;

    private final int maxFrameSize;

    /**
     * The caller saved registers always include all parameter registers.
     */
    private final Register[] callerSaved;

    private final boolean allAllocatableAreCallerSaved;

    private final RegisterAttributes[] attributesMap;

    public int getMaximumFrameSize() {
        return maxFrameSize;
    }

    @Override
    public Register[] getAllocatableRegisters() {
        return allocatable.clone();
    }

    public Register[] filterAllocatableRegisters(PlatformKind kind, Register[] registers) {
        ArrayList<Register> list = new ArrayList<>();
        for (Register reg : registers) {
            if (architecture.canStoreValue(reg.getRegisterCategory(), kind)) {
                list.add(reg);
            }
        }

        Register[] ret = list.toArray(new Register[list.size()]);
        return ret;
    }

    @Override
    public RegisterAttributes[] getAttributesMap() {
        return attributesMap.clone();
    }

    private final Register[] javaGeneralParameterRegisters;
    private final Register[] nativeGeneralParameterRegisters;
    private final Register[] xmmParameterRegisters = {xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7};

    /*
     * Some ABIs (e.g. Windows) require a so-called "home space", that is a save area on the stack
     * to store the argument registers
     */
    private final boolean needsNativeStackHomeSpace;

    private static Register[] initAllocatable(Architecture arch, boolean reserveForHeapBase) {
        Register[] allRegisters = arch.getAvailableValueRegisters();
        Register[] registers = new Register[allRegisters.length - (reserveForHeapBase ? 3 : 2)];

        int idx = 0;
        for (Register reg : allRegisters) {
            if (reg.equals(rsp) || reg.equals(r12)) {
                // skip stack pointer and thread register
                continue;
            }
            if (reserveForHeapBase && reg.equals(r15)) {
                // skip heap base register
                continue;
            }

            registers[idx++] = reg;
        }

        assert idx == registers.length;
        return registers;
    }

    public AMD64HotSpotRegisterConfig(Architecture architecture, HotSpotVMConfig config) {
        this(architecture, config, initAllocatable(architecture, config.useCompressedOops));
        assert callerSaved.length >= allocatable.length;
    }

    public AMD64HotSpotRegisterConfig(Architecture architecture, HotSpotVMConfig config, Register[] allocatable) {
        this.architecture = architecture;
        this.maxFrameSize = config.maxFrameSize;

        if (config.windowsOs) {
            javaGeneralParameterRegisters = new Register[]{rdx, r8, r9, rdi, rsi, rcx};
            nativeGeneralParameterRegisters = new Register[]{rcx, rdx, r8, r9};
            this.needsNativeStackHomeSpace = true;
        } else {
            javaGeneralParameterRegisters = new Register[]{rsi, rdx, rcx, r8, r9, rdi};
            nativeGeneralParameterRegisters = new Register[]{rdi, rsi, rdx, rcx, r8, r9};
            this.needsNativeStackHomeSpace = false;
        }

        this.allocatable = allocatable.clone();
        Set<Register> callerSaveSet = new HashSet<>();
        Collections.addAll(callerSaveSet, allocatable);
        Collections.addAll(callerSaveSet, xmmParameterRegisters);
        Collections.addAll(callerSaveSet, javaGeneralParameterRegisters);
        Collections.addAll(callerSaveSet, nativeGeneralParameterRegisters);
        callerSaved = callerSaveSet.toArray(new Register[callerSaveSet.size()]);

        allAllocatableAreCallerSaved = true;
        attributesMap = RegisterAttributes.createMap(this, architecture.getRegisters());
    }

    @Override
    public Register[] getCallerSaveRegisters() {
        return callerSaved;
    }

    public Register[] getCalleeSaveRegisters() {
        return null;
    }

    @Override
    public boolean areAllAllocatableRegistersCallerSaved() {
        return allAllocatableAreCallerSaved;
    }

    @Override
    public Register getRegisterForRole(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CallingConvention getCallingConvention(Type type, JavaType returnType, JavaType[] parameterTypes, TargetDescription target, boolean stackOnly) {
        if (type == Type.NativeCall) {
            return callingConvention(nativeGeneralParameterRegisters, returnType, parameterTypes, type, target, stackOnly);
        }
        // On x64, parameter locations are the same whether viewed
        // from the caller or callee perspective
        return callingConvention(javaGeneralParameterRegisters, returnType, parameterTypes, type, target, stackOnly);
    }

    public Register[] getCallingConventionRegisters(Type type, JavaKind kind) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Short:
            case Char:
            case Int:
            case Long:
            case Object:
                return type == Type.NativeCall ? nativeGeneralParameterRegisters : javaGeneralParameterRegisters;
            case Float:
            case Double:
                return xmmParameterRegisters;
            default:
                throw JVMCIError.shouldNotReachHere();
        }
    }

    private CallingConvention callingConvention(Register[] generalParameterRegisters, JavaType returnType, JavaType[] parameterTypes, Type type, TargetDescription target, boolean stackOnly) {
        AllocatableValue[] locations = new AllocatableValue[parameterTypes.length];

        int currentGeneral = 0;
        int currentXMM = 0;
        int currentStackOffset = type == Type.NativeCall && needsNativeStackHomeSpace ? generalParameterRegisters.length * target.wordSize : 0;

        for (int i = 0; i < parameterTypes.length; i++) {
            final JavaKind kind = parameterTypes[i].getJavaKind().getStackKind();

            switch (kind) {
                case Byte:
                case Boolean:
                case Short:
                case Char:
                case Int:
                case Long:
                case Object:
                    if (!stackOnly && currentGeneral < generalParameterRegisters.length) {
                        Register register = generalParameterRegisters[currentGeneral++];
                        locations[i] = register.asValue(target.getLIRKind(kind));
                    }
                    break;
                case Float:
                case Double:
                    if (!stackOnly && currentXMM < xmmParameterRegisters.length) {
                        Register register = xmmParameterRegisters[currentXMM++];
                        locations[i] = register.asValue(target.getLIRKind(kind));
                    }
                    break;
                default:
                    throw JVMCIError.shouldNotReachHere();
            }

            if (locations[i] == null) {
                LIRKind lirKind = target.getLIRKind(kind);
                locations[i] = StackSlot.get(lirKind, currentStackOffset, !type.out);
                currentStackOffset += Math.max(lirKind.getPlatformKind().getSizeInBytes(), target.wordSize);
            }
        }

        JavaKind returnKind = returnType == null ? JavaKind.Void : returnType.getJavaKind();
        AllocatableValue returnLocation = returnKind == JavaKind.Void ? Value.ILLEGAL : getReturnRegister(returnKind).asValue(target.getLIRKind(returnKind.getStackKind()));
        return new CallingConvention(currentStackOffset, returnLocation, locations);
    }

    @Override
    public Register getReturnRegister(JavaKind kind) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
            case Long:
            case Object:
                return rax;
            case Float:
            case Double:
                return xmm0;
            case Void:
            case Illegal:
                return null;
            default:
                throw new UnsupportedOperationException("no return register for type " + kind);
        }
    }

    @Override
    public Register getFrameRegister() {
        return rsp;
    }

    @Override
    public String toString() {
        return String.format("Allocatable: " + Arrays.toString(getAllocatableRegisters()) + "%n" + "CallerSave:  " + Arrays.toString(getCallerSaveRegisters()) + "%n");
    }
}
