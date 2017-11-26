/*******************************************************************************
 * Copyright (c) 2009, 2017 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *    
 *******************************************************************************/
package org.jacoco.core.internal.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.internal.analysis.filter.EnumFilter;
import org.jacoco.core.internal.analysis.filter.IFilter;
import org.jacoco.core.internal.analysis.filter.IFilterOutput;
import org.jacoco.core.internal.analysis.filter.LombokGeneratedFilter;
import org.jacoco.core.internal.analysis.filter.PrivateEmptyNoArgConstructorFilter;
import org.jacoco.core.internal.analysis.filter.SynchronizedFilter;
import org.jacoco.core.internal.analysis.filter.SyntheticFilter;
import org.jacoco.core.internal.analysis.filter.TryWithResourcesEcjFilter;
import org.jacoco.core.internal.analysis.filter.TryWithResourcesJavacFilter;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.Instruction;
import org.jacoco.core.internal.flow.LabelInfo;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * A {@link MethodProbesVisitor} that analyzes which statements and branches of
 * a method have been executed based on given probe data.
 */
public class MethodAnalyzer extends MethodProbesVisitor
		implements IFilterOutput {

	private static final IFilter[] FILTERS = new IFilter[] { new EnumFilter(),
			new SyntheticFilter(), new SynchronizedFilter(),
			new TryWithResourcesJavacFilter(), new TryWithResourcesEcjFilter(),
			new PrivateEmptyNoArgConstructorFilter(), new LombokGeneratedFilter() };

	private final String className;

	private final String superClassName;

	private final boolean[] probes;

	private final MethodCoverageImpl coverage;

	private int currentLine = ISourceNode.UNKNOWN_LINE;

	private int firstLine = ISourceNode.UNKNOWN_LINE;

	private int lastLine = ISourceNode.UNKNOWN_LINE;

	// Due to ASM issue #315745 there can be more than one label per instruction
	//一个instruction可能有超过一个label对其指定
	private final List<Label> currentLabel = new ArrayList<Label>(2);//默认2个

	/** List of all analyzed instructions */
	private final List<Instruction> instructions = new ArrayList<Instruction>();

	/** List of all predecessors of covered probes */
	//被覆盖的探针的前任，那也就是说，这个前任是被执行的
	//也就可以理解为这是被执行的指令
	private final List<Instruction> coveredProbes = new ArrayList<Instruction>();

	/** List of all jumps encountered */
	//instruction  Label
	private final List<Jump> jumps = new ArrayList<Jump>();

	/** Last instruction in byte code sequence */
	//字节码序列的上一个指令
	private Instruction lastInsn;

	/**
	 * New Method analyzer for the given probe data.
	 * 
	 * @param className
	 *            class name
	 * @param superClassName
	 *            superclass name
	 * @param name
	 *            method name
	 * @param desc
	 *            method descriptor
	 * @param signature
	 *            optional parameterized signature
	 * 
	 * @param probes
	 *            recorded probe date of the containing class or
	 *            <code>null</code> if the class is not executed at all
	 */
	public MethodAnalyzer(final String className, final String superClassName,
			final String name, final String desc, final String signature,
			final boolean[] probes) {
		super();
		this.className = className;
		this.superClassName = superClassName;
		this.probes = probes;
		//定义一个method的coverage data 对象
		this.coverage = new MethodCoverageImpl(name, desc, signature);
	}

	/**
	 * Returns the coverage data for this method after this visitor has been
	 * processed.
	 * 由ClassAnalyzer調用，判斷method的coverage data
	 * @return coverage data for this method
	 */
	public IMethodCoverage getCoverage() {
		return coverage;
	}

	/**
	 * {@link MethodNode#accept(MethodVisitor)}
	 *应该是让MethodNode访问这个这方法（MethodVisitor）
	 */
	@Override
	public void accept(final MethodNode methodNode,
			final MethodVisitor methodVisitor) {
		this.ignored.clear();
		for (final IFilter filter : FILTERS) {
			filter.filter(className, superClassName, methodNode, this);
		}

		for (final TryCatchBlockNode n : methodNode.tryCatchBlocks) {
			n.accept(methodVisitor);
		}
		currentNode = methodNode.instructions.getFirst();
		while (currentNode != null) {
			currentNode.accept(methodVisitor);
			currentNode = currentNode.getNext();
		}
		methodVisitor.visitEnd();
	}

	private final Set<AbstractInsnNode> ignored = new HashSet<AbstractInsnNode>();
	private AbstractInsnNode currentNode;

	public void ignore(final AbstractInsnNode fromInclusive,
			final AbstractInsnNode toInclusive) {
		for (AbstractInsnNode i = fromInclusive; i != toInclusive; i = i
				.getNext()) {
			ignored.add(i);
		}
		ignored.add(toInclusive);
	}

	@Override
	public void visitLabel(final Label label) {
		currentLabel.add(label);
		//判断该label是否是之前指令的后任者
		if (!LabelInfo.isSuccessor(label)) {
			//如果不是，设置lastInsn为空
			lastInsn = null;
		}
	}

	@Override
	public void visitLineNumber(final int line, final Label start) {
		currentLine = line;
		if (firstLine > line || lastLine == ISourceNode.UNKNOWN_LINE) {
			firstLine = line;
		}
		if (lastLine < line) {
			lastLine = line;
		}
	}

	private void visitInsn() {
		final Instruction insn = new Instruction(currentNode, currentLine);
		//将insn添加到ArrayList instructions中
		instructions.add(insn);
		if (lastInsn != null) {
			insn.setPredecessor(lastInsn);
		} 
		final int labelCount = currentLabel.size();
		if (labelCount > 0) {
			for (int i = labelCount; --i >= 0;) {
				LabelInfo.setInstruction(currentLabel.get(i), insn);
			}
			currentLabel.clear();
		}
		lastInsn = insn;
	}

	@Override
	public void visitInsn(final int opcode) {
		visitInsn();
	}

	@Override
	public void visitIntInsn(final int opcode, final int operand) {
		visitInsn();
	}

	@Override
	public void visitVarInsn(final int opcode, final int var) {
		visitInsn();
	}

	@Override
	public void visitTypeInsn(final int opcode, final String type) {
		visitInsn();
	}

	@Override
	public void visitFieldInsn(final int opcode, final String owner,
			final String name, final String desc) {
		visitInsn();
	}

	@Override
	public void visitMethodInsn(final int opcode, final String owner,
			final String name, final String desc, final boolean itf) {
		visitInsn();
	}

	@Override
	public void visitInvokeDynamicInsn(final String name, final String desc,
			final Handle bsm, final Object... bsmArgs) {
		visitInsn();
	}

	@Override
	public void visitJumpInsn(final int opcode, final Label label) {
		visitInsn();

		jumps.add(new Jump(lastInsn, label));
	}

	@Override
	public void visitLdcInsn(final Object cst) {
		visitInsn();
	}

	@Override
	public void visitIincInsn(final int var, final int increment) {
		visitInsn();
	}

	@Override
	public void visitTableSwitchInsn(final int min, final int max,
			final Label dflt, final Label... labels) {
		visitSwitchInsn(dflt, labels);
	}

	@Override
	public void visitLookupSwitchInsn(final Label dflt, final int[] keys,
			final Label[] labels) {
		visitSwitchInsn(dflt, labels);
	}

	private void visitSwitchInsn(final Label dflt, final Label[] labels) {
		visitInsn();
		LabelInfo.resetDone(labels);
		jumps.add(new Jump(lastInsn, dflt));
		LabelInfo.setDone(dflt);
		for (final Label l : labels) {
			if (!LabelInfo.isDone(l)) {
				jumps.add(new Jump(lastInsn, l));
				LabelInfo.setDone(l);
			}
		}
	}

	@Override
	public void visitMultiANewArrayInsn(final String desc, final int dims) {
		visitInsn();
	}

	@Override
	public void visitProbe(final int probeId) {
		addProbe(probeId);
		lastInsn = null;
	}

	@Override
	public void visitJumpInsnWithProbe(final int opcode, final Label label,
			final int probeId, final IFrame frame) {
		visitInsn();
		addProbe(probeId);
	}

	@Override
	public void visitInsnWithProbe(final int opcode, final int probeId) {
		visitInsn();
		addProbe(probeId);
	}

	@Override
	public void visitTableSwitchInsnWithProbes(final int min, final int max,
			final Label dflt, final Label[] labels, final IFrame frame) {
		visitSwitchInsnWithProbes(dflt, labels);
	}

	@Override
	public void visitLookupSwitchInsnWithProbes(final Label dflt,
			final int[] keys, final Label[] labels, final IFrame frame) {
		visitSwitchInsnWithProbes(dflt, labels);
	}

	private void visitSwitchInsnWithProbes(final Label dflt,
			final Label[] labels) {
		visitInsn();
		LabelInfo.resetDone(dflt);
		LabelInfo.resetDone(labels);
		visitSwitchTarget(dflt);
		for (final Label l : labels) {
			visitSwitchTarget(l);
		}
	}

	private void visitSwitchTarget(final Label label) {
		final int id = LabelInfo.getProbeId(label);
		if (!LabelInfo.isDone(label)) {
			if (id == LabelInfo.NO_PROBE) {
				jumps.add(new Jump(lastInsn, label));
			} else {
				addProbe(id);
			}
			LabelInfo.setDone(label);
		}
	}

//函数访问完毕
	@Override
	public void visitEnd() {
		// Wire jumps:
		for (final Jump j : jumps) {
			LabelInfo.getInstruction(j.target).setPredecessor(j.source);
		}
		// Propagate probe values:
		for (final Instruction p : coveredProbes) {
			p.setCovered();
		}

		// Report result:
		coverage.ensureCapacity(firstLine, lastLine);

		for (final Instruction i : instructions) {
			if (ignored.contains(i.getNode())) {
				continue;
			}

			final int total = i.getBranches();
			final int covered = i.getCoveredBranches();
			//ICounter instrCounter
			//covered == 0,instrCounter=CounterImpl.COUNTER_1_0
			//covered!=0,instrCounter=CounterImpl.COUNTER_0_1
			final ICounter instrCounter = covered == 0 ? CounterImpl.COUNTER_1_0
					: CounterImpl.COUNTER_0_1;
			// ICounter branchCounter
			//total > 1,branchCounter =CounterImpl.getInstance(missed, covered)
			//total=0,branchCounter =CounterImpl.COUNTER_0_0;
			final ICounter branchCounter = total > 1
					? CounterImpl.getInstance(total - covered, covered)
					: CounterImpl.COUNTER_0_0;
			//MethodCoverageImpl
			coverage.increment(instrCounter, branchCounter, i.getLine());
			//得到了complexityCounter
		}
		coverage.incrementMethodCounter();
		//得到了methodCounter和complexityCounter
	}

	private void addProbe(final int probeId) {
		//branch++
		lastInsn.addBranch();
		//probes[probeId]保证其为true
		if (probes != null && probes[probeId]) {
			//添加进coveredProbes中
			coveredProbes.add(lastInsn);
		}
	}

	private static class Jump {

		final Instruction source;
		final Label target;

		Jump(final Instruction source, final Label target) {
			this.source = source;
			this.target = target;
		}
	}

}
