/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.compiler.util;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.cri.*;
import com.oracle.max.graal.debug.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.DeoptimizeNode.DeoptAction;
import com.oracle.max.graal.nodes.calc.*;
import com.oracle.max.graal.nodes.java.*;
import com.oracle.max.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.max.graal.nodes.util.*;

public class InliningUtil {

    public interface InliningCallback {
        StructuredGraph buildGraph(RiResolvedMethod method);
        double inliningWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke);
        void recordConcreteMethodAssumption(RiResolvedMethod method, RiResolvedType context, RiResolvedMethod impl);
    }

    public static String methodName(RiResolvedMethod method) {
        return CiUtil.format("%H.%n(%p):%r", method) + " (" + method.codeSize() + " bytes)";
    }

    private static String methodName(RiResolvedMethod method, Invoke invoke) {
        if (Debug.isLogEnabled()) {
            if (invoke != null && invoke.stateAfter() != null) {
                RiMethod parent = invoke.stateAfter().method();
                return parent.name() + "@" + invoke.bci() + ": " + CiUtil.format("%H.%n(%p):%r", method) + " (" + method.codeSize() + " bytes)";
            } else {
                return CiUtil.format("%H.%n(%p):%r", method) + " (" + method.codeSize() + " bytes)";
            }
        } else {
            return null;
        }
    }

    /**
     * Represents an opportunity for inlining at the given invoke, with the given weight and level.
     * The weight is the amortized weight of the additional code - so smaller is better.
     * The level is the number of nested inlinings that lead to this invoke.
     */
    public abstract static class InlineInfo implements Comparable<InlineInfo> {
        public final Invoke invoke;
        public final double weight;
        public final int level;

        public InlineInfo(Invoke invoke, double weight, int level) {
            this.invoke = invoke;
            this.weight = weight;
            this.level = level;
        }

        @Override
        public int compareTo(InlineInfo o) {
            return (weight < o.weight) ? -1 : (weight > o.weight) ? 1 : 0;
        }

        public abstract boolean canDeopt();

        /**
         * Performs the inlining described by this object and returns the node that represents the return value of the
         * inlined method (or null for void methods and methods that have no non-exceptional exit).
         *
         * @param graph
         * @param runtime
         * @param callback
         * @return The node that represents the return value, or null for void methods and methods that have no
         *         non-exceptional exit.
         */
        public abstract Node inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback);
    }

    /**
     * Represents an inlining opportunity where the compiler can statically determine a monomorphic target method and
     * therefore is able to determine the called method exactly.
     */
    private static class ExactInlineInfo extends InlineInfo {
        public final RiResolvedMethod concrete;

        public ExactInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete) {
            super(invoke, weight, level);
            this.concrete = concrete;
        }

        @Override
        public Node inline(StructuredGraph compilerGraph, GraalRuntime runtime, final InliningCallback callback) {
            StructuredGraph graph = Debug.scope("Inlining", concrete, new Callable<StructuredGraph>() {
                @Override
                public StructuredGraph call() throws Exception {
                    return callback.buildGraph(concrete);
                }
            });
            return InliningUtil.inline(invoke, graph, true);
        }

        @Override
        public String toString() {
            return "exact inlining " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return false;
        }
    }

    /**
     * Represents an inlining opportunity for which profiling information suggests a monomorphic receiver, but for which
     * the receiver type cannot be proven. A type check guard will be generated if this inlining is performed.
     */
    private static class TypeGuardInlineInfo extends ExactInlineInfo {

        public final RiResolvedType type;
        public final double probability;

        public TypeGuardInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete, RiResolvedType type, double probability) {
            super(invoke, weight, level, concrete);
            this.type = type;
            this.probability = probability;
        }

        @Override
        public Node inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            IsTypeNode isType = graph.unique(new IsTypeNode(invoke.callTarget().receiver(), type));
            FixedGuardNode guard = graph.add(new FixedGuardNode(isType));
            assert invoke.predecessor() != null;
            graph.addBeforeFixed(invoke.node(), guard);

            Debug.log("inlining with type check, type probability: %5.3f", probability);
            return super.inline(graph, runtime, callback);
        }

        @Override
        public String toString() {
            return "type-checked inlining " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return true;
        }
    }

    /**
     * Represents an inlining opportunity where the current class hierarchy leads to a monomorphic target method,
     * but for which an assumption has to be registered because of non-final classes.
     */
    private static class AssumptionInlineInfo extends ExactInlineInfo {
        public final RiResolvedType context;

        public AssumptionInlineInfo(Invoke invoke, double weight, int level, RiResolvedType context, RiResolvedMethod concrete) {
            super(invoke, weight, level, concrete);
            this.context = context;
        }

        @Override
        public Node inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            if (Debug.isLogEnabled()) {
                String targetName = CiUtil.format("%H.%n(%p):%r", invoke.callTarget().targetMethod());
                String concreteName = CiUtil.format("%H.%n(%p):%r", concrete);
                Debug.log("recording concrete method assumption: %s on receiver type %s -> %s", targetName, context, concreteName);
            }
            callback.recordConcreteMethodAssumption(invoke.callTarget().targetMethod(), context, concrete);
            return super.inline(graph, runtime, callback);
        }

        @Override
        public String toString() {
            return "inlining with assumption " + CiUtil.format("%H.%n(%p):%r", concrete);
        }

        @Override
        public boolean canDeopt() {
            return true;
        }
    }

    /**
     * Determines if inlining is possible at the given invoke node.
     * @param invoke the invoke that should be inlined
     * @param level the number of nested inlinings that lead to this invoke, or 0 if the invoke was part of the initial graph
     * @param runtime a GraalRuntime instance used to determine of the invoke can be inlined and/or should be intrinsified
     * @param callback a callback that is used to determine the weight of a specific inlining
     * @return an instance of InlineInfo, or null if no inlining is possible at the given invoke
     */
    public static InlineInfo getInlineInfo(Invoke invoke, int level, GraalRuntime runtime, CiAssumptions assumptions, InliningCallback callback) {
        if (!checkInvokeConditions(invoke)) {
            return null;
        }
        RiResolvedMethod parent = invoke.stateAfter().method();
        MethodCallTargetNode callTarget = invoke.callTarget();

        if (callTarget.invokeKind() == InvokeKind.Special || callTarget.targetMethod().canBeStaticallyBound()) {
            if (checkTargetConditions(callTarget.targetMethod())) {
                double weight = callback == null ? 0 : callback.inliningWeight(parent, callTarget.targetMethod(), invoke);
                return new ExactInlineInfo(invoke, weight, level, callTarget.targetMethod());
            }
            return null;
        }
        if (callTarget.receiver().exactType() != null) {
            RiResolvedType exact = callTarget.receiver().exactType();
            assert exact.isSubtypeOf(callTarget.targetMethod().holder()) : exact + " subtype of " + callTarget.targetMethod().holder();
            RiResolvedMethod resolved = exact.resolveMethodImpl(callTarget.targetMethod());
            if (checkTargetConditions(resolved)) {
                double weight = callback == null ? 0 : callback.inliningWeight(parent, resolved, invoke);
                return new ExactInlineInfo(invoke, weight, level, resolved);
            }
            return null;
        }
        RiResolvedType holder = callTarget.targetMethod().holder();

        if (callTarget.receiver().declaredType() != null) {
            RiResolvedType declared = callTarget.receiver().declaredType();
            // the invoke target might be more specific than the holder (happens after inlining: locals lose their declared type...)
            // TODO (ls) fix this
            if (declared != null && declared.isSubtypeOf(holder)) {
                holder = declared;
            }
        }
        // TODO (tw) fix this
        if (assumptions == null) {
            return null;
        }
        RiResolvedMethod concrete = holder.uniqueConcreteMethod(callTarget.targetMethod());
        if (concrete != null) {
            if (checkTargetConditions(concrete)) {
                double weight = callback == null ? 0 : callback.inliningWeight(parent, concrete, invoke);
                return new AssumptionInlineInfo(invoke, weight, level, holder, concrete);
            }
            return null;
        }
        RiTypeProfile profile = parent.typeProfile(invoke.bci());
        if (profile != null && profile.probabilities != null && profile.probabilities.length > 0 && profile.morphism == 1) {
            if (GraalOptions.InlineWithTypeCheck) {
                // type check and inlining...
                concrete = profile.types[0].resolveMethodImpl(callTarget.targetMethod());
                if (concrete != null && checkTargetConditions(concrete)) {
                    double weight = callback == null ? 0 : callback.inliningWeight(parent, concrete, invoke);
                    return new TypeGuardInlineInfo(invoke, weight, level, concrete, profile.types[0], profile.probabilities[0]);
                }
                return null;
            } else {
                Debug.log("not inlining %s because GraalOptions.InlineWithTypeCheck == false", methodName(callTarget.targetMethod(), invoke));
                return null;
            }
        } else {
            Debug.log("not inlining %s because no monomorphic receiver could be found", methodName(callTarget.targetMethod(), invoke));
            return null;
        }
    }

    private static boolean checkInvokeConditions(Invoke invoke) {
        if (invoke.stateAfter() == null) {
            Debug.log("not inlining %s because the invoke has no after state", methodName(invoke.callTarget().targetMethod(), invoke));
            return false;
        }
        if (invoke.predecessor() == null) {
            Debug.log("not inlining %s because the invoke is dead code", methodName(invoke.callTarget().targetMethod(), invoke));
            return false;
        }
        return true;
    }

    private static boolean checkTargetConditions(RiMethod method) {
        if (!(method instanceof RiResolvedMethod)) {
            Debug.log("not inlining %s because it is unresolved", method.toString());
            return false;
        }
        RiResolvedMethod resolvedMethod = (RiResolvedMethod) method;
        if (Modifier.isNative(resolvedMethod.accessFlags())) {
            Debug.log("not inlining %s because it is a native method", methodName(resolvedMethod));
            return false;
        }
        if (Modifier.isAbstract(resolvedMethod.accessFlags())) {
            Debug.log("not inlining %s because it is an abstract method", methodName(resolvedMethod));
            return false;
        }
        if (!resolvedMethod.holder().isInitialized()) {
            Debug.log("not inlining %s because of non-initialized class", methodName(resolvedMethod));
            return false;
        }
        if (!resolvedMethod.canBeInlined()) {
            Debug.log("not inlining %s because it is marked non-inlinable", methodName(resolvedMethod));
            return false;
        }
        return true;
    }

    /**
     * Performs an actual inlining, thereby replacing the given invoke with the given inlineGraph.
     * @param invoke the invoke that will be replaced
     * @param inlineGraph the graph that the invoke will be replaced with
     * @param receiverNullCheck true if a null check needs to be generated for non-static inlinings, false if no such check is required
     * @return The node that represents the return value, or null for void methods and methods that have no non-exceptional exit.
     */
    public static Node inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck) {
        NodeInputList<ValueNode> parameters = invoke.callTarget().arguments();
        StructuredGraph graph = (StructuredGraph) invoke.node().graph();

        FrameState stateAfter = invoke.stateAfter();
        assert stateAfter.isAlive();

        IdentityHashMap<Node, Node> replacements = new IdentityHashMap<>();
        ArrayList<Node> nodes = new ArrayList<>();
        ArrayList<Node> frameStates = new ArrayList<>();
        ReturnNode returnNode = null;
        UnwindNode unwindNode = null;
        BeginNode entryPointNode = inlineGraph.start();
        FixedNode firstCFGNode = entryPointNode.next();
        for (Node node : inlineGraph.getNodes()) {
            if (node == entryPointNode || node == entryPointNode.stateAfter()) {
                // Do nothing.
            } else if (node instanceof LocalNode) {
                replacements.put(node, parameters.get(((LocalNode) node).index()));
            } else {
                nodes.add(node);
                if (node instanceof ReturnNode) {
                    returnNode = (ReturnNode) node;
                } else if (node instanceof UnwindNode) {
                    unwindNode = (UnwindNode) node;
                } else if (node instanceof FrameState) {
                    frameStates.add(node);
                }
            }
        }

        assert invoke.node().successors().first() != null : invoke;
        assert invoke.node().predecessor() != null;

        Map<Node, Node> duplicates = graph.addDuplicates(nodes, replacements);

        FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
        MethodCallTargetNode callTarget = invoke.callTarget();
        if (!callTarget.isStatic() && receiverNullCheck && parameters.get(0).kind() == CiKind.Object && !parameters.get(0).stamp().nonNull()) {
            graph.addBeforeFixed(invoke.node(), graph.add(new FixedGuardNode(graph.unique(new NullCheckNode(parameters.get(0), false)))));
        }
        invoke.node().replaceAtPredecessors(firstCFGNodeDuplicate);

        FrameState stateAtExceptionEdge = null;
        if (invoke instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithException = ((InvokeWithExceptionNode) invoke);
            if (unwindNode != null) {
                assert unwindNode.predecessor() != null;
                assert invokeWithException.exceptionEdge().successors().count() == 1;
                ExceptionObjectNode obj = (ExceptionObjectNode) invokeWithException.exceptionEdge().next();
                stateAtExceptionEdge = obj.stateAfter();
                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                for (Node usage : obj.usages().snapshot()) {
                    usage.replaceFirstInput(obj, unwindDuplicate.exception());
                }
                unwindDuplicate.clearInputs();
                Node n = obj.next();
                obj.setNext(null);
                unwindDuplicate.replaceAndDelete(n);
            } else {
                invokeWithException.killExceptionEdge();
            }
        } else {
            if (unwindNode != null) {
                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                DeoptimizeNode deoptimizeNode = new DeoptimizeNode(DeoptAction.InvalidateRecompile);
                unwindDuplicate.replaceAndDelete(graph.add(deoptimizeNode));
                // move the deopt upwards if there is a monitor exit that tries to use the "after exception" frame state
                // (because there is no "after exception" frame state!)
                if (deoptimizeNode.predecessor() instanceof MonitorExitNode) {
                    MonitorExitNode monitorExit = (MonitorExitNode) deoptimizeNode.predecessor();
                    if (monitorExit.stateAfter() != null && monitorExit.stateAfter().bci == FrameState.AFTER_EXCEPTION_BCI) {
                        FrameState monitorFrameState = monitorExit.stateAfter();
                        graph.removeFixed(monitorExit);
                        monitorFrameState.safeDelete();
                    }
                }
            }
        }

        FrameState stateBefore = null;
        double invokeProbability = invoke.node().probability();
        for (Node node : duplicates.values()) {
            if (GraalOptions.ProbabilityAnalysis) {
                if (node instanceof FixedNode) {
                    FixedNode fixed = (FixedNode) node;
                    fixed.setProbability(fixed.probability() * invokeProbability);
                }
            }
            if (node instanceof FrameState) {
                FrameState frameState = (FrameState) node;
                if (frameState.bci == FrameState.BEFORE_BCI) {
                    if (stateBefore == null) {
                        stateBefore = stateAfter.duplicateModified(invoke.bci(), false, invoke.node().kind(), parameters.toArray(new ValueNode[parameters.size()]));
                    }
                    frameState.replaceAndDelete(stateBefore);
                } else if (frameState.bci == FrameState.AFTER_BCI) {
                    frameState.replaceAndDelete(stateAfter);
                } else if (frameState.bci == FrameState.AFTER_EXCEPTION_BCI) {
                    if (frameState.isAlive()) {
                        assert stateAtExceptionEdge != null;
                        frameState.replaceAndDelete(stateAtExceptionEdge);
                    } else {
                        assert stateAtExceptionEdge == null;
                    }
                }
            }
        }

        Node returnValue = null;
        if (returnNode != null) {
            if (returnNode.result() instanceof LocalNode) {
                returnValue = replacements.get(returnNode.result());
            } else {
                returnValue = duplicates.get(returnNode.result());
            }
            for (Node usage : invoke.node().usages().snapshot()) {
                if (returnNode.result() instanceof LocalNode) {
                    usage.replaceFirstInput(invoke.node(), returnValue);
                } else {
                    usage.replaceFirstInput(invoke.node(), returnValue);
                }
            }
            Node returnDuplicate = duplicates.get(returnNode);
            returnDuplicate.clearInputs();
            Node n = invoke.next();
            invoke.setNext(null);
            if (n instanceof BeginNode) {
                BeginNode begin = (BeginNode) n;
                FixedNode next = begin.next();
                begin.setNext(null);
                returnDuplicate.replaceAndDelete(next);
                begin.safeDelete();
            } else {
                returnDuplicate.replaceAndDelete(n);
            }
        }

        invoke.node().clearInputs();
        invoke.node().replaceAtUsages(null);
        GraphUtil.killCFG(invoke.node());

        // adjust all frame states that were copied
        if (frameStates.size() > 0) {
            FrameState outerFrameState = stateAfter.duplicateModified(invoke.bci(), stateAfter.rethrowException(), invoke.node().kind());
            for (Node node : frameStates) {
                FrameState frameState = (FrameState) duplicates.get(node);
                if (!frameState.isDeleted()) {
                    frameState.setOuterFrameState(outerFrameState);
                }
            }
        }

        if (stateAfter.usages().isEmpty()) {
            stateAfter.safeDelete();
        }
        return returnValue;
    }
}
