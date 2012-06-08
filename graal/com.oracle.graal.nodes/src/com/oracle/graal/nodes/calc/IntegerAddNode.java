/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.calc;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.spi.types.*;

@NodeInfo(shortName = "+")
public class IntegerAddNode extends IntegerArithmeticNode implements Canonicalizable, LIRLowerable, TypeFeedbackProvider {

    public IntegerAddNode(RiKind kind, ValueNode x, ValueNode y) {
        super(kind, x, y);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        if (x().isConstant() && !y().isConstant()) {
            return graph().unique(new IntegerAddNode(kind(), y(), x()));
        }
        if (x().isConstant()) {
            if (kind() == RiKind.Int) {
                return ConstantNode.forInt(x().asConstant().asInt() + y().asConstant().asInt(), graph());
            } else {
                assert kind() == RiKind.Long;
                return ConstantNode.forLong(x().asConstant().asLong() + y().asConstant().asLong(), graph());
            }
        } else if (y().isConstant()) {
            if (kind() == RiKind.Int) {
                int c = y().asConstant().asInt();
                if (c == 0) {
                    return x();
                }
            } else {
                assert kind() == RiKind.Long;
                long c = y().asConstant().asLong();
                if (c == 0) {
                    return x();
                }
            }
            // canonicalize expressions like "(a + 1) + 2"
            if (x() instanceof IntegerAddNode) {
                IntegerAddNode other = (IntegerAddNode) x();
                if (other.y().isConstant()) {
                    ConstantNode sum;
                    if (kind() == RiKind.Int) {
                        sum = ConstantNode.forInt(y().asConstant().asInt() + other.y().asConstant().asInt(), graph());
                    } else {
                        assert kind() == RiKind.Long;
                        sum = ConstantNode.forLong(y().asConstant().asLong() + other.y().asConstant().asLong(), graph());
                    }
                    return graph().unique(new IntegerAddNode(kind(), other.x(), sum));
                }
            }
        }
        return this;
    }

    public static boolean isIntegerAddition(ValueNode result, ValueNode a, ValueNode b) {
        RiKind kind = result.kind();
        if (kind != a.kind() || kind != b.kind() || !(kind.isInt() || kind.isLong())) {
            return false;
        }
        if (result.isConstant() && a.isConstant() && b.isConstant()) {
            if (kind.isInt()) {
                return result.asConstant().asInt() == a.asConstant().asInt() + b.asConstant().asInt();
            } else if (kind.isLong()) {
                return result.asConstant().asLong() == a.asConstant().asLong() + b.asConstant().asLong();
            }
        } else if (result instanceof IntegerAddNode) {
            IntegerAddNode add = (IntegerAddNode) result;
            return (add.x() == a && add.y() == b) || (add.y() == a && add.x() == b);
        }
        return false;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        RiValue op1 = gen.operand(x());
        assert op1 != null : x() + ", this=" + this;
        RiValue op2 = gen.operand(y());
        if (!y().isConstant() && !FloatAddNode.livesLonger(this, y(), gen)) {
            RiValue op = op1;
            op1 = op2;
            op2 = op;
        }
        gen.setResult(this, gen.emitAdd(op1, op2));
    }

    @Override
    public void typeFeedback(TypeFeedbackTool tool) {
        if (y().isConstant() && !x().isConstant()) {
            tool.addScalar(this).setTranslated(y().asConstant(), tool.queryScalar(x()));
        } else if (x().isConstant() && !y().isConstant()) {
            tool.addScalar(this).setTranslated(x().asConstant(), tool.queryScalar(y()));
        }
    }
}
