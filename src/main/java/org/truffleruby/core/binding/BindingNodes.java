/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.binding;

import java.util.LinkedHashSet;
import java.util.Set;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayHelpers;
import org.truffleruby.core.cast.NameToJavaStringNodeGen;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.locals.ReadFrameSlotNode;
import org.truffleruby.language.locals.ReadFrameSlotNodeGen;
import org.truffleruby.language.locals.WriteFrameSlotNode;
import org.truffleruby.language.locals.WriteFrameSlotNodeGen;
import org.truffleruby.language.objects.AllocateObjectNode;
import org.truffleruby.parser.Translator;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CreateCast;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.object.DynamicObject;

@CoreClass("Binding")
public abstract class BindingNodes {

    public static DynamicObject createBinding(RubyContext context, MaterializedFrame frame) {
        return Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), frame, null);
    }

    public static DynamicObject createBinding(RubyContext context, MaterializedFrame frame, FrameDescriptor extrasDescriptor) {
        return Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), frame, newExtrasFrame(frame, extrasDescriptor));
    }

    @TruffleBoundary
    public static FrameDescriptor newFrameDescriptor(RubyContext context) {
        return new FrameDescriptor(context.getCoreLibrary().getNil());
    }

    @TruffleBoundary
    public static FrameDescriptor newFrameDescriptor(RubyContext context, String name) {
        FrameDescriptor frameDescriptor = new FrameDescriptor(context.getCoreLibrary().getNil());
        frameDescriptor.findOrAddFrameSlot(name);
        return frameDescriptor;
    }

    public static FrameDescriptor getFrameDescriptor(DynamicObject binding) {
        assert RubyGuards.isRubyBinding(binding);
        return getTopFrame(binding).getFrameDescriptor();
    }

    public static MaterializedFrame getTopFrame(DynamicObject binding) {
        assert RubyGuards.isRubyBinding(binding);
        MaterializedFrame frame = Layouts.BINDING.getExtras(binding);
        if (frame != null) {
            return frame;
        } else {
            return Layouts.BINDING.getFrame(binding);
        }
    }

    public static MaterializedFrame getExtrasFrame(RubyContext context, DynamicObject binding) {
        assert RubyGuards.isRubyBinding(binding);
        MaterializedFrame frame = Layouts.BINDING.getExtras(binding);
        if (frame == null) {
            frame = newExtrasFrame(context, Layouts.BINDING.getFrame(binding));
            Layouts.BINDING.setExtras(binding, frame);
        }
        return frame;
    }

    public static MaterializedFrame newExtrasFrame(DynamicObject binding, FrameDescriptor frameDescriptor) {
        assert RubyGuards.isRubyBinding(binding);
        MaterializedFrame frame = getTopFrame(binding);
        frame = newExtrasFrame(frame, frameDescriptor);
        Layouts.BINDING.setExtras(binding, frame);
        return frame;
    }

    public static MaterializedFrame newExtrasFrame(RubyContext context, MaterializedFrame parent) {
        FrameDescriptor descriptor = newFrameDescriptor(context);
        return newExtrasFrame(parent, descriptor);
    }

    public static MaterializedFrame newExtrasFrame(MaterializedFrame parent, FrameDescriptor descriptor) {
        final MaterializedFrame frame = Truffle.getRuntime().createVirtualFrame(
                RubyArguments.pack(
                        parent,
                        null,
                        RubyArguments.getMethod(parent),
                        RubyArguments.getDeclarationContext(parent),
                        null,
                        RubyArguments.getSelf(parent),
                        RubyArguments.getBlock(parent),
                        RubyArguments.getArguments(parent)),
                descriptor).materialize();
        return frame;
    }

    protected static class FrameSlotAndDepth {
        private final FrameSlot slot;
        private final int depth;

        public FrameSlotAndDepth(FrameSlot slot, int depth) {
            this.slot = slot;
            this.depth = depth;
        }

        public FrameSlot getSlot() {
            return slot;
        }
    }

    public static FrameSlotAndDepth findFrameSlotOrNull(String identifier, MaterializedFrame frame) {
        int depth = 0;
        while (frame != null) {
            final FrameSlot frameSlot = frame.getFrameDescriptor().findFrameSlot(identifier);
            if (frameSlot != null) {
                return new FrameSlotAndDepth(frameSlot, depth);
            }

            frame = RubyArguments.getDeclarationFrame(frame);
            depth++;
        }
        return null;
    }

    public static FrameSlotAndDepth findFrameSlotOrNull(String identifier, FrameDescriptor frameDescriptor) {
        final FrameSlot frameSlot = frameDescriptor.findFrameSlot(identifier);
        assert frameSlot != null;
        return new FrameSlotAndDepth(frameSlot, 0);
    }

    public static boolean hiddenVariable(String name) {
        return name.startsWith("$") || name.startsWith("rubytruffle_temp");
    }

    @CoreMethod(names = { "dup", "clone" })
    public abstract static class DupNode extends UnaryCoreMethodNode {

        @Child private AllocateObjectNode allocateObjectNode = AllocateObjectNode.create();

        @Specialization
        public DynamicObject dup(DynamicObject binding) {
            MaterializedFrame frame = getTopFrame(binding);
            Layouts.BINDING.setExtras(binding, newExtrasFrame(getContext(), frame));
            DynamicObject copy = allocateObjectNode.allocate(
                    Layouts.BASIC_OBJECT.getLogicalClass(binding),
                    newExtrasFrame(getContext(), frame),
                    null);
            return copy;
        }
    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_defined?", required = 1)
    @NodeChildren({
        @NodeChild(type = RubyNode.class, value = "binding"),
        @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class LocalVariableDefinedNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public boolean localVariableDefinedUncached(DynamicObject binding, String name) {
            return findFrameSlotOrNull(name, getTopFrame(binding)) != null;
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableDefinedLastLine(DynamicObject binding, String name) {
            throw new RaiseException(coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_get", required = 1)
    @NodeChildren({
        @NodeChild(type = RubyNode.class, value = "binding"),
        @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class LocalVariableGetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "cachedFrameSlot != null",
                "getFrameDescriptor(binding) == descriptor"
        }, limit = "getCacheLimit()")
        public Object localVariableGetCached(DynamicObject binding, String name,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor descriptor,
                @Cached("findFrameSlotOrNull(name, getTopFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("createReadNode(cachedFrameSlot)") ReadFrameSlotNode readLocalVariableNode) {
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(getTopFrame(binding), cachedFrameSlot.depth);
            return readLocalVariableNode.executeRead(frame);
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public Object localVariableGetUncached(DynamicObject binding, String name) {
            MaterializedFrame frame = getTopFrame(binding);
            FrameSlotAndDepth frameSlot = findFrameSlotOrNull(name, frame);
            if (frameSlot != null) {
                return RubyArguments.getDeclarationFrame(frame, frameSlot.depth).getValue(frameSlot.slot);
            } else {
                throw new RaiseException(coreExceptions().nameErrorLocalVariableNotDefined(name, binding, this));
            }
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableGetLastLine(DynamicObject binding, String name) {
            throw new RaiseException(coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

        protected ReadFrameSlotNode createReadNode(FrameSlotAndDepth frameSlot) {
            if (frameSlot == null) {
                return null;
            } else {
                return ReadFrameSlotNodeGen.create(frameSlot.slot);
            }
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_set", required = 2)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "binding"),
            @NodeChild(type = RubyNode.class, value = "name"),
            @NodeChild(type = RubyNode.class, value = "value")
    })
    public abstract static class LocalVariableSetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
                "cachedFrameSlot != null"
        }, limit = "getCacheLimit()")
        public Object localVariableSetCached(DynamicObject binding, String name, Object value,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                @Cached("findFrameSlotOrNull(name, getTopFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("createWriteNode(cachedFrameSlot)") WriteFrameSlotNode writeLocalVariableNode) {
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(getTopFrame(binding), cachedFrameSlot.depth);
            return writeLocalVariableNode.executeWrite(frame, value);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
                "cachedFrameSlot == null"
        }, limit = "getCacheLimit()")
        public Object localVariableSetNewCached(DynamicObject binding, String name, Object value,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                @Cached("findFrameSlotOrNull(name, getTopFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("newFrameDescriptor(getContext(), name)") FrameDescriptor newDescriptor,
                @Cached("findFrameSlotOrNull(name, newDescriptor)") FrameSlotAndDepth newFrameSlot,
                @Cached("createWriteNode(newFrameSlot)") WriteFrameSlotNode writeLocalVariableNode) {
            final MaterializedFrame frame = newExtrasFrame(binding, newDescriptor);
            return writeLocalVariableNode.executeWrite(frame, value);
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public Object localVariableSetUncached(DynamicObject binding, String name, Object value) {
            MaterializedFrame frame = getTopFrame(binding);
            final FrameSlotAndDepth frameSlot = findFrameSlotOrNull(name, frame);
            final FrameSlot slot;
            if (frameSlot != null) {
                frame = RubyArguments.getDeclarationFrame(frame, frameSlot.depth);
                slot = frameSlot.slot;
            } else {
                frame = newExtrasFrame(binding, newFrameDescriptor(getContext(), name));
                slot = frame.getFrameDescriptor().findFrameSlot(name);
            }
            frame.setObject(slot, value);
            return value;
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableSetLastLine(DynamicObject binding, String name, Object value) {
            throw new RaiseException(coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

        protected WriteFrameSlotNode createWriteNode(FrameSlotAndDepth frameSlot) {
            return WriteFrameSlotNodeGen.create(frameSlot.slot);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }
    }

    @CoreMethod(names = "local_variables")
    public abstract static class LocalVariablesNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject localVariables(DynamicObject binding) {
            return listLocalVariables(getContext(), getTopFrame(binding));
        }

        @TruffleBoundary
        public static DynamicObject listLocalVariables(RubyContext context, Frame frame) {
            final Set<Object> names = new LinkedHashSet<>();
            while (frame != null) {
                addNamesFromFrame(context, frame, names);

                frame = RubyArguments.getDeclarationFrame(frame);
            }
            final int size = names.size();
            return ArrayHelpers.createArray(context, names.toArray(new Object[size]), size);
        }

        private static void addNamesFromFrame(RubyContext context, Frame frame, final Set<Object> names) {
            for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
                if (slot.getIdentifier() instanceof String &&
                        !hiddenVariable((String) slot.getIdentifier()) &&
                        !Translator.FRAME_LOCAL_GLOBAL_VARIABLES.contains(slot.getIdentifier())) {
                    names.add(context.getSymbolTable().getSymbol((String) slot.getIdentifier()));
                }
            }
        }

    }

    @CoreMethod(names = "receiver")
    public abstract static class ReceiverNode extends UnaryCoreMethodNode {

        @Specialization
        public Object receiver(DynamicObject binding) {
            return RubyArguments.getSelf(Layouts.BINDING.getFrame(binding));
        }
    }

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

}
