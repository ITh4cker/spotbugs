/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.daveho.ba;

import java.util.*;

// We require BCEL 5.1 or later.
import org.apache.bcel.*;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

/**
 * Family of dataflow analyses for counting the number of locks held
 * at points in a method.  Subclasses just need to override
 * the initEntryFact() and getDelta() methods.
 *
 * @see Dataflow
 * @see DataflowAnalysis
 * @see LockCount
 * @author David Hovemeyer
 */
public abstract class LockCountAnalysis extends ForwardDataflowAnalysis<LockCount> {

	private static final boolean DEBUG = Boolean.getBoolean("dataflow.debug");

	protected MethodGen methodGen;
	protected Dataflow<ThisValueFrame> tvaDataflow;

	/**
	 * Constructor.
	 * @param methodGen method being analyzed
	 * @param tvaDataflow dataflow results indicating which frame slots contain the "this" reference
	 *   (allows us to distinguish self locks from other locks, may be null if
	 *   the method is static, or if we just don't care about the distinction)
	 */
	public LockCountAnalysis(MethodGen methodGen, Dataflow<ThisValueFrame> tvaDataflow) {
		this.methodGen = methodGen;
		this.tvaDataflow = tvaDataflow;
	}

	public LockCount createFact() {
		return new LockCount(LockCount.BOTTOM);
	}

	public void copy(LockCount source, LockCount dest) {
		dest.setCount(source.getCount());
	}

	public void initResultFact(LockCount result) {
		result.setCount(LockCount.TOP);
	}

	public void makeFactTop(LockCount fact) {
		fact.setCount(LockCount.TOP);
	}

	public boolean same(LockCount fact1, LockCount fact2) {
		return fact1.getCount() == fact2.getCount();
	}

	public void transfer(BasicBlock basicBlock, InstructionHandle end, LockCount start, LockCount result) throws DataflowAnalysisException {
		result.setCount(start.getCount());

		if (!start.isTop() && !start.isBottom()) {
			Iterator<InstructionHandle> i = basicBlock.instructionIterator();
			while (i.hasNext()) {
				InstructionHandle handle = i.next();
				if (handle == end)
					break;

				Instruction ins = handle.getInstruction();

				// Optimization: don't even bother with instructions
				// other than MONITORENTER and MONITOREXIT
				if (!(ins instanceof MONITORENTER || ins instanceof MONITOREXIT))
					continue;

				// Determine where the "this" reference values are in the frame.
				// (Note that null is returned if we are analyzing a static method.)
				ThisValueFrame frame = getFrame(basicBlock, handle);

				// Get the lock count delta for the instruction
				int delta = getDelta(ins, frame);
				int count = result.getCount() + delta;
				if (count < 0)
					throw new DataflowAnalysisException("lock count going negative! " + ins);
				result.setCount(count);
			}
		}
	}

	/**
	 * Get the frame value indicating where the "this" references are
	 * for given instruction in given basic block.
	 * @param basicBlock the basic block
	 * @param handle the instruction
	 * @return the ThisValueFrame containing the "this" references, or null if
	 *    this is a static method
	 */
	private ThisValueFrame getFrame(BasicBlock basicBlock, InstructionHandle handle) throws DataflowAnalysisException {
		ThisValueFrame frame = null;
		if (tvaDataflow != null) {
			DataflowAnalysis<ThisValueFrame> tva = tvaDataflow.getAnalysis();
			frame = tva.createFact();
			tva.transfer(basicBlock, handle, tvaDataflow.getStartFact(basicBlock), frame);
		}
		return frame;
	}

	public void meetInto(LockCount fact, Edge edge, LockCount result) throws DataflowAnalysisException {
		if (edge.getDest().isExceptionHandler()) {
			// WARNING!

			// Subtle special case - on a handled exception where the last instruction
			// in the source basic block affects the lock count, we must undo the change
			// to the lock count before merging the value into the start value for
			// the exception handler.  In other words, the exception that was thrown
			// by a monitorexit exception means that the monitorexit did not happen.

			// FIXME: I really need to think of a more general way to incorporate this
			// into the dataflow analysis framework.

			BasicBlock source = edge.getSource();
			InstructionHandle last = source.getLastInstruction();
			ThisValueFrame frame = getFrame(edge.getSource(), last);
			int delta = getDelta(last.getInstruction(), frame);
			if (delta != 0) {
				if (DEBUG) System.out.print("[[Undo lock count delta for source block " + source.getId() + "]]");
				LockCount tmpFact = new LockCount(fact.getCount() - delta); // undo the lock operation
				fact = tmpFact;
			}
		}

		// Standard lattice thing
		if (fact.isTop() || result.isBottom())
			; // no change
		else if (result.isTop())
			result.setCount(fact.getCount());
		else if (fact.isBottom())
			result.setCount(LockCount.BOTTOM);
		else if (fact.getCount() == result.getCount())
			; // no change
		else
			result.setCount(LockCount.BOTTOM);
	}

	/**
	 * Get the lock count delta resulting from the execution of the given instruction.
	 * @param ins the instruction
	 * @param frame Frame indicating which stack locations hold the value
	 *   of the "this" reference; will be null for static methods
	 */
	public abstract int getDelta(Instruction ins, ThisValueFrame frame) throws DataflowAnalysisException;

}

// vim:ts=4
