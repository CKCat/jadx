package jadx.core.dex.visitors;

import jadx.core.Consts;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.attributes.AttributeType;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.IndexInsnNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.LiteralArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.instructions.mods.ConstructorInsn;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.TryCatchBlock;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.exceptions.JadxRuntimeException;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visitor for modify method instructions
 * (remove, replace, process exception handlers)
 */
public class ModVisitor extends AbstractVisitor {
	private static final Logger LOG = LoggerFactory.getLogger(ModVisitor.class);

	@Override
	public void visit(MethodNode mth) {
		if (mth.isNoCode())
			return;

		removeStep(mth);
		replaceStep(mth);

		checkArgsNames(mth);

		for (BlockNode block : mth.getBasicBlocks()) {
			processExceptionHander(mth, block);
		}
	}

	private void replaceStep(MethodNode mth) {
		for (BlockNode block : mth.getBasicBlocks()) {
			InstructionRemover remover = new InstructionRemover(block.getInstructions());

			int size = block.getInstructions().size();
			for (int i = 0; i < size; i++) {
				InsnNode insn = block.getInstructions().get(i);

				switch (insn.getType()) {
					case INVOKE:
						InvokeNode inv = (InvokeNode) insn;
						MethodInfo callMth = inv.getCallMth();
						if (callMth.isConstructor()) {
							ConstructorInsn co = new ConstructorInsn(mth, inv);
							if (co.isSuper()) {
								try {
									// don't call 'super' if parent 'Object'
									if (!co.getClassType().getFullName().equals(Consts.CLASS_OBJECT)) {
										for (int j = 0; j < co.getArgsCount(); j++) {
											InsnArg arg = co.getArg(j);
											if (arg.isRegister()) {
												CodeShrinker.inlineArgument(mth, (RegisterArg) arg);
											}
										}
										if (!mth.getParentClass().getAccessFlags().isEnum())
											mth.setSuperCall(co);
									}
									remover.add(insn);
								} catch (JadxRuntimeException e) {
									// inline args into super fail
									LOG.warn("Can't inline args into super call: " + inv + ", mth: " + mth);
									replaceInsn(block, i, co);
								}
							} else if (co.isThis() && co.getArgsCount() == 0) {
								MethodNode defCo = mth.getParentClass()
										.searchMethodByName(co.getCallMth().getShortId());
								if (defCo == null || defCo.isNoCode()) {
									// default constructor not implemented
									remover.add(insn);
								} else {
									replaceInsn(block, i, co);
								}
							} else {
								replaceInsn(block, i, co);
							}
						}
						break;

					case CONST:
						ClassNode parentClass = mth.getParentClass();
						FieldNode f = null;
						if (insn.getArgsCount() == 0) {
							// const-string
							IndexInsnNode node = (IndexInsnNode) insn;
							f = parentClass.getConstField(node.getIndex());
						} else {
							LiteralArg arg = (LiteralArg) insn.getArg(0);
							ArgType type = arg.getType();
							long lit = arg.getLiteral();
							if (Math.abs(lit) > 0xFF) {
								if (type.equals(ArgType.INT))
									f = parentClass.getConstField((int) lit);
								else if (type.equals(ArgType.LONG))
									f = parentClass.getConstField(lit);
							}
							if (type.equals(ArgType.DOUBLE))
								f = parentClass.getConstField(Double.longBitsToDouble(lit));
							else if (type.equals(ArgType.FLOAT))
								f = parentClass.getConstField(Float.intBitsToFloat((int) lit));
						}
						if (f != null) {
							InsnNode inode = new IndexInsnNode(InsnType.SGET, f.getFieldInfo(), 0);
							inode.setResult(insn.getResult());
							replaceInsn(block, i, inode);
						}
						break;

					default:
						break;
				}
			}
			remover.perform();
		}
	}

	/**
	 * Remove unnecessary instructions
	 */
	private void removeStep(MethodNode mth) {
		for (BlockNode block : mth.getBasicBlocks()) {
			InstructionRemover remover = new InstructionRemover(block.getInstructions());

			int size = block.getInstructions().size();
			for (int i = 0; i < size; i++) {
				InsnNode insn = block.getInstructions().get(i);

				switch (insn.getType()) {
					case NOP:
					case GOTO:
					case NEW_INSTANCE:
						remover.add(insn);
						break;

					case NEW_ARRAY:
						// create array in 'fill-array' instruction
						int next = i + 1;
						if (next < size) {
							InsnNode ni = block.getInstructions().get(next);
							if (ni.getType() == InsnType.FILL_ARRAY) {
								ni.getResult().merge(insn.getResult());
								remover.add(insn);
							}
						}
						break;

					case RETURN:
						if (insn.getArgsCount() == 0) {
							if (mth.getBasicBlocks().size() == 1 && i == size - 1) {
								remover.add(insn);
							} else if (mth.getMethodInfo().isClassInit()) {
								remover.add(insn);
							}
						}
						break;

					default:
						break;
				}
			}
			remover.perform();
		}
	}

	private void processExceptionHander(MethodNode mth, BlockNode block) {
		ExcHandlerAttr handlerAttr = (ExcHandlerAttr) block.getAttributes().get(AttributeType.EXC_HANDLER);
		if (handlerAttr == null)
			return;

		TryCatchBlock tryBlock = handlerAttr.getTryBlock();
		ExceptionHandler excHandler = handlerAttr.getHandler();
		List<InsnNode> blockInsns = block.getInstructions();
		int size = blockInsns.size();
		if (size > 0 && blockInsns.get(0).getType() == InsnType.MOVE_EXCEPTION) {
			InstructionRemover.remove(block, 0);
		}

		int totalSize = 0;
		boolean noExitNode = true; // check if handler has exit edge to block not from this handler
		for (BlockNode excBlock : excHandler.getBlocks()) {
			List<InsnNode> insns = excBlock.getInstructions();
			size = insns.size();
			if (noExitNode)
				noExitNode = excHandler.getBlocks().containsAll(excBlock.getCleanSuccessors());

			if (excHandler.isCatchAll()
					&& size > 0
					&& insns.get(size - 1).getType() == InsnType.THROW) {

				InstructionRemover.remove(excBlock, size - 1);
				size = insns.size();

				// move not removed instructions to 'finally' block
				if (size != 0) {
					// TODO: support instructions from several blocks
					// tryBlock.setFinalBlockFromInsns(mth, insns);

					// TODO; because of incomplete realization don't extract final block,
					// just remove unnecessary instructions
					insns.clear();

					size = insns.size();
				}
			}
			totalSize += size;
		}
		if (totalSize == 0 && noExitNode)
			tryBlock.removeHandler(mth, excHandler);
	}

	/**
	 * Replace insn by index i in block,
	 * for proper copy attributes, assume attributes are not overlap
	 */
	private static void replaceInsn(BlockNode block, int i, InsnNode insn) {
		InsnNode prevInsn = block.getInstructions().get(i);
		insn.getAttributes().addAll(prevInsn.getAttributes());
		block.getInstructions().set(i, insn);
	}

	/**
	 * Replace oldInsn in block by newInsn,
	 */
	public static boolean replaceInsn(BlockNode block, InsnNode oldInsn, InsnNode newInsn) {
		int pos = BlockUtils.insnIndex(block, oldInsn);
		if (pos == -1)
			return false;

		replaceInsn(block, pos, newInsn);
		return true;
	}

	private void checkArgsNames(MethodNode mth) {
		for (RegisterArg arg : mth.getArguments(false)) {
			String name = arg.getTypedVar().getName();
			if (name != null && NameMapper.isReserved(name)) {
				name = name + "_";
				arg.getTypedVar().setName(name);
			}
		}
	}
}