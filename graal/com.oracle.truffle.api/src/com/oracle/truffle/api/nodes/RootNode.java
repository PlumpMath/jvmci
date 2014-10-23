/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.nodes;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.source.*;

/**
 * A root node is a node with a method to execute it given only a frame as a parameter. Therefore, a
 * root node can be used to create a call target using
 * {@link TruffleRuntime#createCallTarget(RootNode)}.
 */
public abstract class RootNode extends Node {

    private RootCallTarget callTarget;
    @CompilationFinal private FrameDescriptor frameDescriptor;

    protected RootNode() {
        this(null, null);
    }

    protected RootNode(SourceSection sourceSection) {
        this(sourceSection, null);
    }

    protected RootNode(SourceSection sourceSection, FrameDescriptor frameDescriptor) {
        super(sourceSection);
        if (frameDescriptor == null) {
            this.frameDescriptor = new FrameDescriptor();
        } else {
            this.frameDescriptor = frameDescriptor;
        }
    }

    @Override
    public Node copy() {
        RootNode root = (RootNode) super.copy();
        root.frameDescriptor = frameDescriptor.shallowCopy();
        return root;
    }

    /**
     * Returns <code>true</code> if this {@link RootNode} is allowed to be cloned. The runtime
     * system might decide to create deep copies of the {@link RootNode} in order to gather context
     * sensitive profiling feedback. The default implementation returns <code>false</code>. Guest
     * language specific implementations may want to return <code>true</code> here to indicate that
     * gathering call site specific profiling information might make sense for this {@link RootNode}
     * .
     *
     * @return <code>true</code> if cloning is allowed else <code>false</code>.
     */
    public boolean isCloningAllowed() {
        return false;
    }

    /**
     * Reports the execution count of a loop that is a child of this node. The optimization
     * heuristics can use the loop count to guide compilation and inlining.
     */
    public final void reportLoopCount(int count) {
        if (getCallTarget() instanceof LoopCountReceiver) {
            ((LoopCountReceiver) getCallTarget()).reportLoopCount(count);
        }
    }

    /**
     * Executes this function using the specified frame and returns the result value.
     *
     * @param frame the frame of the currently executing guest language method
     * @return the value of the execution
     */
    public abstract Object execute(VirtualFrame frame);

    public final RootCallTarget getCallTarget() {
        return callTarget;
    }

    public final FrameDescriptor getFrameDescriptor() {
        return frameDescriptor;
    }

    public final void setCallTarget(RootCallTarget callTarget) {
        this.callTarget = callTarget;
    }

}
