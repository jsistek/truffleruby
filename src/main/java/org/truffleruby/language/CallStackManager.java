/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import org.truffleruby.Layouts;
import org.truffleruby.Main;
import org.truffleruby.RubyContext;
import org.truffleruby.collections.Memo;
import org.truffleruby.core.module.ModuleOperations;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.backtrace.Activation;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.backtrace.BacktraceFormatter;
import org.truffleruby.language.backtrace.InternalRootNode;
import org.truffleruby.language.exceptions.DisablingBacktracesNode;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.SharedMethodInfo;

import java.util.ArrayList;

public class CallStackManager {

    private final RubyContext context;

    public CallStackManager(RubyContext context) {
        this.context = context;
    }

    private static final Object STOP_ITERATING = new Object();

    public FrameInstance getCallerFrameIgnoringSend() {
        return getCallerFrameIgnoringSend(0);
    }

    public Node getCallerNode() {
        return getCallerNode(0);
    }

    @TruffleBoundary
    public Node getCallerNode(int skip) {
        // Try first using getCallerFrame() as it's the common case
        if (skip == 0) {
            FrameInstance callerFrame = Truffle.getRuntime().getCurrentFrame();
            if (callerFrame == null) {
                return null;
            }
            Node node = callerFrame.getCallNode();
            if (node != null) {
                return node;
            }
        }

        // Need to iterate further
        return Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Node>() {
            int depth = 0;
            int skipped = 0;

            @Override
            public Node visitFrame(FrameInstance frameInstance) {
                depth++;
                if (depth == 1) { // Skip top frame
                    return null;
                }

                if (skipped >= skip) {
                    return frameInstance.getCallNode();
                } else {
                    skipped++;
                }
                return null;
            }
        });
    }

    @TruffleBoundary
    public boolean callerIsSend() {
        FrameInstance callerFrame = Truffle.getRuntime().getCallerFrame();
        if (callerFrame == null) {
            return false;
        }
        InternalMethod method = getMethod(callerFrame);
        return context.getCoreLibrary().isSend(method);
    }

    @TruffleBoundary
    public FrameInstance getCallerFrameIgnoringSend(int skip) {
        // Try first using getCallerFrame() as it's the common case
        if (skip == 0) {
            FrameInstance callerFrame = Truffle.getRuntime().getCallerFrame();
            if (callerFrame == null) {
                return null;
            }
            InternalMethod method = getMethod(callerFrame);

            if (method == null) { // Not a Ruby frame
                return null;
            } else if (!context.getCoreLibrary().isSend(method)) {
                return callerFrame;
            }
        }

        // Need to iterate further
        final Object frame = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Object>() {
            int depth = 0;
            int skipped = 0;

            @Override
            public Object visitFrame(FrameInstance frameInstance) {
                depth++;
                if (depth == 1) { // Skip top frame
                    return null;
                }

                InternalMethod method = getMethod(frameInstance);
                if (method == null) {
                    return STOP_ITERATING;
                } else if (!context.getCoreLibrary().isSend(method)) {
                    if (skipped >= skip) {
                        return frameInstance;
                    } else {
                        skipped++;
                        return null;
                    }
                } else {
                    return null;
                }
            }
        });

        if (frame instanceof FrameInstance) {
            return (FrameInstance) frame;
        } else {
            return null;
        }
    }

    @TruffleBoundary
    public InternalMethod getCallingMethodIgnoringSend() {
        return getMethod(getCallerFrameIgnoringSend());
    }

    @TruffleBoundary
    public Node getTopMostUserCallNode() {
        final Memo<Boolean> firstFrame = new Memo<>(true);

        return Truffle.getRuntime().iterateFrames(frameInstance -> {
            if (firstFrame.get()) {
                firstFrame.set(false);
                return null;
            }

            final SourceSection sourceSection = frameInstance.getCallNode().getEncapsulatingSourceSection();

            if (sourceSection.getSource() == null) {
                return null;
            } else {
                return frameInstance.getCallNode();
            }
        });
    }

    private InternalMethod getMethod(FrameInstance frame) {
        return RubyArguments.tryGetMethod(frame.getFrame(FrameInstance.FrameAccess.READ_ONLY));
    }

    public void printBacktrace(Node currentNode) {
        final Backtrace backtrace = context.getCallStack().getBacktrace(currentNode);
        final BacktraceFormatter formatter = BacktraceFormatter.createDefaultFormatter(context);
        formatter.printBacktrace(context, null, backtrace);
    }

    public Backtrace getBacktrace(Node currentNode, Throwable javaThrowable) {
        return getBacktrace(currentNode, 0, false, null, javaThrowable);
    }

    public Backtrace getBacktrace(Node currentNode) {
        return getBacktrace(currentNode, 0, false, null, null);
    }

    public Backtrace getBacktrace(Node currentNode, int omit) {
        return getBacktrace(currentNode, omit, false, null, null);
    }

    public Backtrace getBacktrace(Node currentNode, int omit, DynamicObject exception) {
        return getBacktrace(currentNode, omit, false, exception, null);
    }

    public Backtrace getBacktrace(Node currentNode,
                                  final int omit,
                                  final boolean filterNullSourceSection,
                                  DynamicObject exception) {
        return getBacktrace(currentNode, omit, filterNullSourceSection, exception, null);
    }

    @TruffleBoundary
    public Backtrace getBacktrace(Node currentNode,
                                  final int omit,
                                  final boolean filterNullSourceSection,
                                  DynamicObject exception,
                                  Throwable javaThrowable) {
        if (exception != null
                && context.getOptions().BACKTRACES_OMIT_UNUSED
                && DisablingBacktracesNode.areBacktracesDisabled()
                && ModuleOperations.assignableTo(Layouts.BASIC_OBJECT.getLogicalClass(exception),
                    context.getCoreLibrary().getStandardErrorClass())) {
            return new Backtrace(currentNode, new Activation[] { Activation.OMITTED_UNUSED }, null);
        }

        final int limit = context.getOptions().BACKTRACES_LIMIT;

        final ArrayList<Activation> activations = new ArrayList<>();

        if (omit == 0 && currentNode != null && Truffle.getRuntime().getCurrentFrame() != null) {
            final InternalMethod method = RubyArguments.tryGetMethod(Truffle.getRuntime().getCurrentFrame()
                    .getFrame(FrameInstance.FrameAccess.READ_ONLY));

            activations.add(new Activation(currentNode, method));
        }

        final Memo<Boolean> firstFrame = new Memo<>(true);

        Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Object>() {
            int depth = 1;

            @Override
            public Object visitFrame(FrameInstance frameInstance) {
                if (firstFrame.get()) {
                    firstFrame.set(false);
                    return null;
                }

                if (depth > limit) {
                    activations.add(Activation.OMITTED_LIMIT);
                    return new Object();
                }

                if (!ignoreFrame(frameInstance) && depth >= omit) {
                    if (!(filterNullSourceSection && hasNullSourceSection(frameInstance))) {
                        final InternalMethod method = RubyArguments.tryGetMethod(frameInstance
                                .getFrame(FrameInstance.FrameAccess.READ_ONLY));

                        Node callNode = getCallNode(frameInstance, method);
                        if (callNode != null) {
                            activations.add(new Activation(callNode, method));
                        }
                    }
                }

                depth++;

                return null;
            }

        });

        if (!activations.isEmpty()) {
            final Activation last = activations.get(activations.size() - 1);
            if (last.getCallNode().getRootNode().getSourceSection().getSource().getName() == Main.BOOT_SOURCE_NAME) {
                activations.remove(activations.size() - 1);
            }
        }

        if (context.getOptions().EXCEPTIONS_STORE_JAVA || context.getOptions().BACKTRACES_INTERLEAVE_JAVA) {
            if (javaThrowable == null) {
                javaThrowable = new Exception();
            }
        } else {
            javaThrowable = null;
        }

        return new Backtrace(currentNode, activations.toArray(new Activation[activations.size()]), javaThrowable);
    }

    private boolean ignoreFrame(FrameInstance frameInstance) {
        final Node callNode = frameInstance.getCallNode();

        // Nodes with no call node are top-level or require, which *should* appear in the backtrace.
        if (callNode == null) {
            return false;
        }

        final RootNode rootNode = callNode.getRootNode();

        // Ignore the call to Truffle::Boot.main
        if (rootNode instanceof RubyRootNode) {
            SharedMethodInfo sharedMethodInfo = ((RubyRootNode) rootNode).getSharedMethodInfo();
            if (context.getCoreLibrary().isTruffleBootMainMethod(sharedMethodInfo)) {
                return true;
            }
        }

        if (rootNode instanceof InternalRootNode) {
            return true;
        }

        if (callNode.getEncapsulatingSourceSection() == null) {
            return true;
        }

        return false;
    }

    private boolean hasNullSourceSection(FrameInstance frameInstance) {
        final Node callNode = frameInstance.getCallNode();
        if (callNode == null) {
            return true;
        }

        final SourceSection sourceSection = callNode.getEncapsulatingSourceSection();
        return sourceSection == null || sourceSection.getSource() == null;
    }

    private Node getCallNode(FrameInstance frameInstance, final InternalMethod method) {
        Node callNode = frameInstance.getCallNode();
        if (callNode == null && method != null &&
                BacktraceFormatter.isCore(context, method.getSharedMethodInfo().getSourceSection())) {
            callNode = ((RootCallTarget) method.getCallTarget()).getRootNode();
        }
        return callNode;
    }

}
