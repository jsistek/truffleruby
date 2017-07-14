/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.kernel;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CallerFrameAccess;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.Hashing;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.basicobject.BasicObjectNodes;
import org.truffleruby.core.basicobject.BasicObjectNodes.ObjectIDNode;
import org.truffleruby.core.basicobject.BasicObjectNodes.ReferenceEqualNode;
import org.truffleruby.core.basicobject.BasicObjectNodesFactory;
import org.truffleruby.core.basicobject.BasicObjectNodesFactory.ObjectIDNodeFactory;
import org.truffleruby.core.binding.BindingNodes;
import org.truffleruby.core.cast.BooleanCastNode;
import org.truffleruby.core.cast.BooleanCastNodeGen;
import org.truffleruby.core.cast.BooleanCastWithDefaultNodeGen;
import org.truffleruby.core.cast.DurationToMillisecondsNodeGen;
import org.truffleruby.core.cast.NameToJavaStringNode;
import org.truffleruby.core.cast.NameToJavaStringNodeGen;
import org.truffleruby.core.cast.NameToSymbolOrStringNodeGen;
import org.truffleruby.core.cast.TaintResultNode;
import org.truffleruby.core.cast.ToPathNodeGen;
import org.truffleruby.core.cast.ToStrNodeGen;
import org.truffleruby.core.format.BytesResult;
import org.truffleruby.core.format.FormatExceptionTranslator;
import org.truffleruby.core.format.exceptions.FormatException;
import org.truffleruby.core.format.exceptions.InvalidFormatException;
import org.truffleruby.core.format.printf.PrintfCompiler;
import org.truffleruby.core.kernel.KernelNodesFactory.CopyNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.SameOrEqualNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.SingletonMethodsNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.ToHexStringNodeFactory;
import org.truffleruby.core.method.MethodFilter;
import org.truffleruby.core.proc.ProcNodes.ProcNewNode;
import org.truffleruby.core.proc.ProcNodesFactory.ProcNewNodeFactory;
import org.truffleruby.core.proc.ProcOperations;
import org.truffleruby.core.proc.ProcType;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.rubinius.TypeNodes.ObjectInstanceVariablesNode;
import org.truffleruby.core.rubinius.TypeNodesFactory.ObjectInstanceVariablesNodeFactory;
import org.truffleruby.core.string.StringCachingGuards;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.core.symbol.SymbolTable;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.backtrace.Activation;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.dispatch.DispatchAction;
import org.truffleruby.language.dispatch.DispatchHeadNode;
import org.truffleruby.language.dispatch.DispatchNode;
import org.truffleruby.language.dispatch.DoesRespondDispatchHeadNode;
import org.truffleruby.language.dispatch.MissingBehavior;
import org.truffleruby.language.dispatch.RubyCallNode;
import org.truffleruby.language.globals.ReadGlobalVariableNodeGen;
import org.truffleruby.language.loader.CodeLoader;
import org.truffleruby.language.loader.RequireNode;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.LookupMethodNode;
import org.truffleruby.language.methods.LookupMethodNodeGen;
import org.truffleruby.language.methods.SharedMethodInfo;
import org.truffleruby.language.objects.FreezeNode;
import org.truffleruby.language.objects.FreezeNodeGen;
import org.truffleruby.language.objects.IsANode;
import org.truffleruby.language.objects.IsFrozenNode;
import org.truffleruby.language.objects.IsFrozenNodeGen;
import org.truffleruby.language.objects.IsTaintedNode;
import org.truffleruby.language.objects.LogicalClassNode;
import org.truffleruby.language.objects.LogicalClassNodeGen;
import org.truffleruby.language.objects.MetaClassNode;
import org.truffleruby.language.objects.MetaClassNodeGen;
import org.truffleruby.language.objects.ObjectIVarGetNode;
import org.truffleruby.language.objects.ObjectIVarGetNodeGen;
import org.truffleruby.language.objects.ObjectIVarSetNode;
import org.truffleruby.language.objects.ObjectIVarSetNodeGen;
import org.truffleruby.language.objects.PropagateTaintNode;
import org.truffleruby.language.objects.PropertyFlags;
import org.truffleruby.language.objects.ReadObjectFieldNode;
import org.truffleruby.language.objects.ReadObjectFieldNodeGen;
import org.truffleruby.language.objects.SelfNode;
import org.truffleruby.language.objects.ShapeCachingGuards;
import org.truffleruby.language.objects.SingletonClassNode;
import org.truffleruby.language.objects.SingletonClassNodeGen;
import org.truffleruby.language.objects.TaintNode;
import org.truffleruby.language.objects.WriteObjectFieldNode;
import org.truffleruby.language.objects.WriteObjectFieldNodeGen;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.parser.ParserContext;
import org.truffleruby.parser.TranslatorDriver;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
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
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;

@CoreClass("Kernel")
public abstract class KernelNodes {

    /**
     * Check if operands are the same object or call #==.
     * Known as rb_equal() in MRI. The fact Kernel#=== uses this is pure coincidence.
     */
    @CoreMethod(names = "===", required = 1)
    public abstract static class SameOrEqualNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode equalNode;

        private final ConditionProfile sameProfile = ConditionProfile.createBinaryProfile();

        public abstract boolean executeSameOrEqual(VirtualFrame frame, Object a, Object b);

        @Specialization
        public boolean sameOrEqual(VirtualFrame frame, Object a, Object b,
                @Cached("create()") ReferenceEqualNode referenceEqualNode) {
            if (sameProfile.profile(referenceEqualNode.executeReferenceEqual(a, b))) {
                return true;
            } else {
                return areEqual(frame, a, b);
            }
        }

        private boolean areEqual(VirtualFrame frame, Object left, Object right) {
            if (equalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                equalNode = insert(CallDispatchHeadNode.create());
            }

            return equalNode.callBoolean(frame, left, "==", null, right);
        }

    }

    /** Check if operands are the same object or call #eql? */
    public abstract static class SameOrEqlNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode eqlNode;

        private final ConditionProfile sameProfile = ConditionProfile.createBinaryProfile();

        public abstract boolean executeSameOrEql(VirtualFrame frame, Object a, Object b);

        @Specialization
        public boolean sameOrEql(VirtualFrame frame, Object a, Object b,
                        @Cached("create()") ReferenceEqualNode referenceEqualNode) {
            if (sameProfile.profile(referenceEqualNode.executeReferenceEqual(a, b))) {
                return true;
            } else {
                return areEql(frame, a, b);
            }
        }

        private boolean areEql(VirtualFrame frame, Object left, Object right) {
            if (eqlNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                eqlNode = insert(CallDispatchHeadNode.create());
            }
            return eqlNode.callBoolean(frame, left, "eql?", null, right);
        }

    }

    @CoreMethod(names = { "<=>" }, required = 1)
    public abstract static class CompareNode extends CoreMethodArrayArgumentsNode {

        @Child private SameOrEqualNode equalNode = SameOrEqualNodeFactory.create(null);

        @Specialization
        public Object compare(VirtualFrame frame, Object self, Object other) {
            if (equalNode.executeSameOrEqual(frame, self, other)) {
                return 0;
            } else {
                return nil();
            }
        }

    }

    @CoreMethod(names = "binding", isModuleFunction = true)
    public abstract static class BindingNode extends CoreMethodArrayArgumentsNode {

        public abstract DynamicObject executeBinding(VirtualFrame frame);
        
        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.MATERIALIZE);

        @Specialization
        public DynamicObject binding(VirtualFrame frame) {
            // Materialize the caller's frame - false means don't use a slow path to get it - we want to optimize it
            final MaterializedFrame callerFrame = callerFrameNode.execute(frame).materialize();

            return BindingNodes.createBinding(getContext(), callerFrame);
        }
    }

    @CoreMethod(names = "block_given?", isModuleFunction = true)
    public abstract static class BlockGivenNode extends CoreMethodArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.ARGUMENTS);

        @Specialization
        public boolean blockGiven(VirtualFrame frame,
                @Cached("createBinaryProfile()") ConditionProfile blockProfile) {
            Frame callerFrame = callerFrameNode.execute(frame);
            return blockProfile.profile(RubyArguments.getBlock(callerFrame) != null);
        }

    }

    @CoreMethod(names = "__callee__", isModuleFunction = true)
    public abstract static class CalleeNameNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject calleeName() {
            // the "called name" of a method.
            return getSymbol(getContext().getCallStack().getCallingMethodIgnoringSend().getName());
        }
    }

    @CoreMethod(names = "caller_locations", isModuleFunction = true, optional = 2, lowerFixnum = { 1, 2 })
    public abstract static class CallerLocationsNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject callerLocations(NotProvided omit, NotProvided length) {
            return callerLocations(1, -1);
        }

        @Specialization
        public DynamicObject callerLocations(int omit, NotProvided length) {
            return callerLocations(omit, -1);
        }

        @Specialization
        public DynamicObject callerLocations(int omit, int length) {
            final Backtrace backtrace = getContext().getCallStack().getBacktrace(this, 1 + omit, true, null);

            int locationsCount = backtrace.getActivations().size();

            if (length != -1 && locationsCount > length) {
                locationsCount = length;
            }

            final Object[] locations = new Object[locationsCount];

            for (int n = 0; n < locationsCount; n++) {
                Activation activation = backtrace.getActivations().get(n);
                locations[n] = Layouts.THREAD_BACKTRACE_LOCATION.createThreadBacktraceLocation(coreLibrary().getThreadBacktraceLocationFactory(), activation);
            }

            return createArray(locations, locations.length);
        }
    }

    @CoreMethod(names = "class")
    public abstract static class KernelClassNode extends CoreMethodArrayArgumentsNode {

        @Child private LogicalClassNode classNode = LogicalClassNodeGen.create(null);

        @Specialization
        public DynamicObject getClass(VirtualFrame frame, Object self) {
            return classNode.executeLogicalClass(self);
        }

    }

    @ImportStatic(ShapeCachingGuards.class)
    public abstract static class CopyNode extends UnaryCoreMethodNode {

        @Child private CallDispatchHeadNode allocateNode = CallDispatchHeadNode.createOnSelf();

        public abstract DynamicObject executeCopy(VirtualFrame frame, DynamicObject self);

        @ExplodeLoop
        @Specialization(guards = "self.getShape() == cachedShape", limit = "getCacheLimit()")
        protected DynamicObject copyCached(VirtualFrame frame, DynamicObject self,
                @Cached("self.getShape()") Shape cachedShape,
                @Cached("getLogicalClass(cachedShape)") DynamicObject logicalClass,
                @Cached(value = "getUserProperties(cachedShape)", dimensions = 1) Property[] properties,
                @Cached("createReadFieldNodes(properties)") ReadObjectFieldNode[] readFieldNodes,
                @Cached("createWriteFieldNodes(properties)") WriteObjectFieldNode[] writeFieldNodes) {
            final DynamicObject newObject = (DynamicObject) allocateNode.call(frame, logicalClass, "__allocate__");

            for (int i = 0; i < properties.length; i++) {
                final Object value = readFieldNodes[i].execute(self);
                writeFieldNodes[i].execute(newObject, value);
            }

            return newObject;
        }

        @Specialization(guards = "updateShape(self)")
        protected Object updateShapeAndCopy(VirtualFrame frame, DynamicObject self) {
            return executeCopy(frame, self);
        }

        @Specialization(replaces = { "copyCached", "updateShapeAndCopy" })
        protected DynamicObject copyUncached(VirtualFrame frame, DynamicObject self) {
            final DynamicObject rubyClass = Layouts.BASIC_OBJECT.getLogicalClass(self);
            final DynamicObject newObject = (DynamicObject) allocateNode.call(frame, rubyClass, "__allocate__");
            copyInstanceVariables(self, newObject);
            return newObject;
        }

        protected DynamicObject getLogicalClass(Shape shape) {
            return Layouts.BASIC_OBJECT.getLogicalClass(shape.getObjectType());
        }

        protected Property[] getUserProperties(Shape shape) {
            CompilerAsserts.neverPartOfCompilation();

            final List<Property> userProperties = new ArrayList<>();

            for (Property property : shape.getProperties()) {
                if (property.getKey() instanceof String) {
                    userProperties.add(property);
                }
            }

            return userProperties.toArray(new Property[userProperties.size()]);
        }

        protected ReadObjectFieldNode[] createReadFieldNodes(Property[] properties) {
            final ReadObjectFieldNode[] nodes = new ReadObjectFieldNode[properties.length];
            for (int i = 0; i < properties.length; i++) {
                nodes[i] = ReadObjectFieldNodeGen.create(properties[i].getKey(), nil());
            }
            return nodes;
        }

        protected WriteObjectFieldNode[] createWriteFieldNodes(Property[] properties) {
            final WriteObjectFieldNode[] nodes = new WriteObjectFieldNode[properties.length];
            for (int i = 0; i < properties.length; i++) {
                nodes[i] = WriteObjectFieldNodeGen.create(properties[i].getKey());
            }
            return nodes;
        }

        @TruffleBoundary
        private void copyInstanceVariables(DynamicObject from, DynamicObject to) {
            // Concurrency: OK if callers create the object and publish it after copy
            // Only copy user-level instance variables, hidden ones are initialized later with #initialize_copy.
            for (Property property : getUserProperties(from.getShape())) {
                to.define(property.getKey(), property.get(from, from.getShape()), property.getFlags());
            }
        }

        protected int getCacheLimit() {
            return getContext().getOptions().INSTANCE_VARIABLE_CACHE;
        }

    }

    @CoreMethod(names = "clone")
    public abstract static class CloneNode extends CoreMethodArrayArgumentsNode {

        @Child private CopyNode copyNode = CopyNodeFactory.create(null);
        @Child private CallDispatchHeadNode initializeCloneNode = CallDispatchHeadNode.createOnSelf();
        @Child private IsFrozenNode isFrozenNode = IsFrozenNodeGen.create(null);
        @Child private FreezeNode freezeNode;
        @Child private PropagateTaintNode propagateTaintNode = PropagateTaintNode.create();
        @Child private SingletonClassNode singletonClassNode = SingletonClassNodeGen.create(null);

        @Specialization
        public DynamicObject clone(VirtualFrame frame, DynamicObject self,
                @Cached("createBinaryProfile()") ConditionProfile isSingletonProfile,
                @Cached("createBinaryProfile()") ConditionProfile isFrozenProfile,
                @Cached("createBinaryProfile()") ConditionProfile isRubyClass) {
            final DynamicObject newObject = copyNode.executeCopy(frame, self);

            // Copy the singleton class if any.
            final DynamicObject selfMetaClass = Layouts.BASIC_OBJECT.getMetaClass(self);
            if (isSingletonProfile.profile(Layouts.CLASS.getIsSingleton(selfMetaClass))) {
                final DynamicObject newObjectMetaClass = singletonClassNode.executeSingletonClass(newObject);
                Layouts.MODULE.getFields(newObjectMetaClass).initCopy(selfMetaClass);
            }

            initializeCloneNode.call(frame, newObject, "initialize_clone", self);

            propagateTaintNode.propagate(self, newObject);

            if (isFrozenProfile.profile(isFrozenNode.executeIsFrozen(self))) {
                if (freezeNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    freezeNode = insert(FreezeNodeGen.create(null));
                }

                freezeNode.executeFreeze(newObject);
            }

            if (isRubyClass.profile(RubyGuards.isRubyClass(self))) {
                Layouts.CLASS.setSuperclass(newObject, Layouts.CLASS.getSuperclass(self));
            }

            return newObject;
        }

    }

    @CoreMethod(names = "dup", taintFrom = 0)
    public abstract static class DupNode extends CoreMethodArrayArgumentsNode {

        @Child private CopyNode copyNode = CopyNodeFactory.create(null);
        @Child private CallDispatchHeadNode initializeDupNode = CallDispatchHeadNode.createOnSelf();

        @Specialization
        public DynamicObject dup(VirtualFrame frame, DynamicObject self) {
            final DynamicObject newObject = copyNode.executeCopy(frame, self);

            initializeDupNode.call(frame, newObject, "initialize_dup", self);

            return newObject;
        }

    }

    @CoreMethod(names = "eval", isModuleFunction = true, required = 1, optional = 3, lowerFixnum = 4)
    @NodeChildren({
            @NodeChild(value = "source", type = RubyNode.class),
            @NodeChild(value = "binding", type = RubyNode.class),
            @NodeChild(value = "file", type = RubyNode.class),
            @NodeChild(value = "line", type = RubyNode.class)
    })
    @ImportStatic({ StringCachingGuards.class, StringOperations.class })
    public abstract static class EvalNode extends CoreMethodNode {

        @Child private CallDispatchHeadNode toStr;
        @Child private BindingNode bindingNode;
        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.MATERIALIZE);

        @CreateCast("source")
        public RubyNode coerceSourceToString(RubyNode source) {
            return ToStrNodeGen.create(source);
        }

        protected DynamicObject getCallerBinding(VirtualFrame frame) {
            if (bindingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                bindingNode = insert(KernelNodesFactory.BindingNodeFactory.create(null));
            }

            return bindingNode.executeBinding(frame);
        }

        protected static class RootNodeWrapper {
            private final RubyRootNode rootNode;

            public RootNodeWrapper(RubyRootNode rootNode) {
                this.rootNode = rootNode;
            }

            public RubyRootNode getRootNode() {
                return rootNode;
            }
        }

        // We always cache against the caller's frame descriptor to avoid breaking assumptions about
        // the shapes of declaration frames made in other areas. Specifically other code in the
        // runtime assumes that a frame with a particular shape will always have a chain of
        // declaration frame also of stable shapes. This assumption could be broken by code such
        // as eval("binding") which does not depend on a declaration context from the parses point
        // of view but could produce frames that broke the assumption.
        @Specialization(guards = {
                "isRubyString(source)",
                "equalNode.execute(rope(source), cachedSource)",
                "callerDescriptor == callerFrameNode.execute(frame).getFrameDescriptor()",
        }, limit = "getCacheLimit()")
        public Object evalNoBindingCached(
                VirtualFrame frame,
                DynamicObject source,
                NotProvided binding,
                NotProvided file,
                NotProvided line,
                @Cached("privatizeRope(source)") Rope cachedSource,
                @Cached("callerFrameNode.execute(frame).getFrameDescriptor()") FrameDescriptor callerDescriptor,
                @Cached("compileSource(source, callerFrameNode.execute(frame).materialize())") RootNodeWrapper cachedRootNode,
                @Cached("createCallTarget(cachedRootNode)") CallTarget cachedCallTarget,
                @Cached("create(cachedCallTarget)") DirectCallNode callNode,
                @Cached("create()") RopeNodes.EqualNode equalNode) {
            final MaterializedFrame parentFrame = callerFrameNode.execute(frame).materialize();
            return eval(cachedRootNode, cachedCallTarget, callNode, parentFrame);
        }

        @Specialization(guards = {
                "isRubyString(source)"
        }, replaces = "evalNoBindingCached")
        public Object evalNoBindingUncached(VirtualFrame frame, DynamicObject source, NotProvided noBinding, NotProvided file, NotProvided line,
                @Cached("create()") IndirectCallNode callNode) {
            final DynamicObject binding = getCallerBinding(frame);
            final MaterializedFrame topFrame = Layouts.BINDING.getFrame(binding);
            RubyArguments.setSelf(topFrame, RubyArguments.getSelf(frame));
            final CodeLoader.DeferredCall deferredCall = doEvalX(source, binding, "(eval)", 1, true);
            return deferredCall.call(callNode);

        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isNil(noBinding)",
                "isRubyString(file)"
        })
        public Object evalNilBinding(VirtualFrame frame, DynamicObject source, DynamicObject noBinding, DynamicObject file, Object unusedLine,
                @Cached("create()") IndirectCallNode callNode) {
            return evalNoBindingUncached(frame, source, NotProvided.INSTANCE, NotProvided.INSTANCE, NotProvided.INSTANCE, callNode);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "equalNode.execute(rope(source), cachedSource)",
                "isRubyBinding(binding)",
                "assignsNoNewVariables(cachedRootNode)",
                "bindingDescriptor == getBindingDescriptor(binding)"
        }, limit = "getCacheLimit()")
        public Object evalBindingCached(
                DynamicObject source,
                DynamicObject binding,
                NotProvided file,
                NotProvided line,
                @Cached("privatizeRope(source)") Rope cachedSource,
                @Cached("getBindingDescriptor(binding)") FrameDescriptor bindingDescriptor,
                @Cached("compileSource(source, getBindingFrame(binding))") RootNodeWrapper cachedRootNode,
                @Cached("createCallTarget(cachedRootNode)") CallTarget cachedCallTarget,
                @Cached("create(cachedCallTarget)") DirectCallNode callNode,
                @Cached("create()") RopeNodes.EqualNode equalNode) {
            final MaterializedFrame parentFrame = BindingNodes.getTopFrame(binding);
            return eval(cachedRootNode, cachedCallTarget, callNode, parentFrame);
        }

        private Object eval(final RootNodeWrapper rootNode, final CallTarget callTarget, final DirectCallNode callNode, final MaterializedFrame parentFrame) {
            final Object bindingSelf = RubyArguments.getSelf(parentFrame);

            final InternalMethod method = new InternalMethod(
                    getContext(),
                    rootNode.getRootNode().getSharedMethodInfo(),
                    RubyArguments.getMethod(parentFrame).getLexicalScope(),
                    rootNode.getRootNode().getSharedMethodInfo().getName(),
                    RubyArguments.getMethod(parentFrame).getDeclaringModule(),
                    Visibility.PUBLIC,
                    callTarget);

            return callNode.call(RubyArguments.pack(parentFrame, null, method, RubyArguments.getDeclarationContext(parentFrame), null, bindingSelf, null, new Object[]{}));
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "equalNode.execute(rope(source), cachedSource)",
                "isRubyBinding(binding)",
                "!assignsNoNewVariables(cachedRootNode)",
                "assignsNoNewVariables(rootNodeToEval)",
                "bindingDescriptor == getBindingDescriptor(binding)"
        }, limit = "getCacheLimit()")
        public Object evalBindingAddsVarsCached(
                DynamicObject source,
                DynamicObject binding,
                NotProvided file,
                NotProvided line,
                @Cached("privatizeRope(source)") Rope cachedSource,
                @Cached("getBindingDescriptor(binding)") FrameDescriptor bindingDescriptor,
                @Cached("compileSource(source, getBindingFrame(binding))") RootNodeWrapper cachedRootNode,
                @Cached("newBindingDescriptor(getContext(), cachedRootNode)") FrameDescriptor newBindingDescriptor,
                @Cached("compileSource(source, getBindingFrame(binding), newBindingDescriptor)") RootNodeWrapper rootNodeToEval,
                @Cached("createCallTarget(rootNodeToEval)") CallTarget cachedCallTarget,
                @Cached("create(cachedCallTarget)") DirectCallNode callNode,
                @Cached("create()") RopeNodes.EqualNode equalNode) {
            final MaterializedFrame parentFrame = BindingNodes.newExtrasFrame(binding,
                    newBindingDescriptor);
            return eval(rootNodeToEval, cachedCallTarget, callNode, parentFrame);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isRubyBinding(binding)"
        })
        public Object evalBinding(VirtualFrame frame, DynamicObject source, DynamicObject binding, NotProvided file, NotProvided line,
                @Cached("create()") IndirectCallNode callNode) {
            final CodeLoader.DeferredCall deferredCall = doEvalX(source, binding, "(eval)", 1, false);
            return deferredCall.call(callNode);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isRubyBinding(binding)",
                "isNil(noFile)",
                "isNil(noLine)"
        })
        public Object evalBinding(VirtualFrame frame, DynamicObject source, DynamicObject binding, DynamicObject noFile, DynamicObject noLine,
                @Cached("create()") IndirectCallNode callNode) {
            return evalBinding(frame, source, binding, NotProvided.INSTANCE, NotProvided.INSTANCE, callNode);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isRubyBinding(binding)",
                "isRubyString(file)" })
        public Object evalBindingFilename(VirtualFrame frame, DynamicObject source, DynamicObject binding, DynamicObject file, NotProvided line,
                @Cached("create()") IndirectCallNode callNode) {
            return evalBindingFilenameLine(frame, source, binding, file, 0, callNode);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isRubyBinding(binding)",
                "isRubyString(file)",
                "isNil(noLine)"
        })
        public Object evalBindingFilename(VirtualFrame frame, DynamicObject source, DynamicObject binding, DynamicObject file, DynamicObject noLine,
                @Cached("create()") IndirectCallNode callNode) {
            return evalBindingFilename(frame, source, binding, file, NotProvided.INSTANCE, callNode);
        }

        @Specialization(guards = {
                "isRubyString(source)",
                "isRubyBinding(binding)",
                "isRubyString(file)" })
        public Object evalBindingFilenameLine(VirtualFrame frame, DynamicObject source, DynamicObject binding, DynamicObject file, int line,
                @Cached("create()") IndirectCallNode callNode) {
            final CodeLoader.DeferredCall deferredCall = doEvalX(source, binding, file.toString(), line, false);
            return deferredCall.call(callNode);
        }

        @TruffleBoundary
        @Specialization(guards = {
                "isRubyString(source)",
                "!isRubyBinding(badBinding)" })
        public Object evalBadBinding(DynamicObject source, DynamicObject badBinding, NotProvided file, NotProvided line) {
            throw new RaiseException(coreExceptions().typeErrorWrongArgumentType(badBinding, "binding", this));
        }

        @TruffleBoundary
        private CodeLoader.DeferredCall doEvalX(DynamicObject rubySource,
                DynamicObject binding,
                String file,
                int line,
                boolean ownScopeForAssignments) {
            final Rope code = StringOperations.rope(rubySource);

            // TODO (pitr 15-Oct-2015): fix this ugly hack, required for AS, copy-paste
            final String s = new String(new char[Math.max(line - 1, 0)]);
            final String space = StringUtils.replace(s, "\0", "\n");
            // TODO CS 14-Apr-15 concat space + code as a rope, otherwise the string will be copied
            // after the rope is converted
            final Source source = Source.newBuilder(space + RopeOperations.decodeRope(code)).name(file).mimeType(RubyLanguage.MIME_TYPE).build();

            final MaterializedFrame frame = BindingNodes.getExtrasFrame(getContext(), binding);
            final DeclarationContext declarationContext = RubyArguments.getDeclarationContext(frame);
            final RubyRootNode rootNode = getContext().getCodeLoader().parse(
                    source, code.getEncoding(), ParserContext.EVAL, frame, ownScopeForAssignments, this);
            return getContext().getCodeLoader().prepareExecute(
                    ParserContext.EVAL, declarationContext, rootNode, frame, RubyArguments.getSelf(frame));
        }

        protected RootNodeWrapper compileSource(VirtualFrame frame, DynamicObject sourceText) {
            assert RubyGuards.isRubyString(sourceText);

            final DynamicObject callerBinding = getCallerBinding(frame);
            final MaterializedFrame parentFrame = Layouts.BINDING.getFrame(callerBinding);

            final Encoding encoding = Layouts.STRING.getRope(sourceText).getEncoding();
            final Source source = Source.newBuilder(sourceText.toString()).name("(eval)").mimeType(RubyLanguage.MIME_TYPE).build();

            final TranslatorDriver translator = new TranslatorDriver(getContext());

            return new RootNodeWrapper(translator.parse(source, encoding, ParserContext.EVAL, null, null, parentFrame, true, this));
        }

        protected RootNodeWrapper compileSource(DynamicObject sourceText, MaterializedFrame parentFrame) {
            assert RubyGuards.isRubyString(sourceText);

            final Encoding encoding = Layouts.STRING.getRope(sourceText).getEncoding();
            final Source source = Source.newBuilder(sourceText.toString()).name("(eval)").mimeType(RubyLanguage.MIME_TYPE).build();

            final TranslatorDriver translator = new TranslatorDriver(getContext());

            return new RootNodeWrapper(translator.parse(source, encoding, ParserContext.EVAL, null, null, parentFrame, true, this));
        }

        protected RootNodeWrapper compileSource(DynamicObject sourceText, MaterializedFrame parentFrame, FrameDescriptor additionalVariables) {
            return compileSource(sourceText, BindingNodes.newExtrasFrame(parentFrame, additionalVariables));
        }

        protected CallTarget createCallTarget(RootNodeWrapper rootNode) {
            return Truffle.getRuntime().createCallTarget(rootNode.rootNode);
        }

        protected FrameDescriptor getBindingDescriptor(DynamicObject binding) {
            return BindingNodes.getFrameDescriptor(binding);
        }

        protected FrameDescriptor newBindingDescriptor(RubyContext context, RootNodeWrapper rootNode) {
            FrameDescriptor descriptor = rootNode.getRootNode().getFrameDescriptor();
            FrameDescriptor newDescriptor = new FrameDescriptor(context.getCoreLibrary().getNil());
            for (FrameSlot s : descriptor.getSlots()) {
                newDescriptor.findOrAddFrameSlot(s.getIdentifier());
            }
            return newDescriptor;
        }

        protected MaterializedFrame getBindingFrame(DynamicObject binding) {
            return BindingNodes.getTopFrame(binding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().EVAL_CACHE;
        }

        protected boolean assignsNoNewVariables(RootNodeWrapper rootNode) {
            FrameDescriptor descriptor = rootNode.getRootNode().getFrameDescriptor();
            return descriptor.getSize() == 1 && SelfNode.SELF_IDENTIFIER.equals(descriptor.getSlots().get(0).getIdentifier());
        }
    }

    @CoreMethod(names = "freeze")
    public abstract static class KernelFreezeNode extends CoreMethodArrayArgumentsNode {

        @Child private FreezeNode freezeNode = FreezeNodeGen.create(null);

        @Specialization
        public Object freeze(Object self) {
            return freezeNode.executeFreeze(self);
        }

    }

    @CoreMethod(names = "frozen?")
    public abstract static class KernelFrozenNode extends CoreMethodArrayArgumentsNode {

        @Child private IsFrozenNode isFrozenNode;

        @Specialization
        public boolean isFrozen(Object self) {
            if (isFrozenNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isFrozenNode = insert(IsFrozenNodeGen.create(null));
            }

            return isFrozenNode.executeIsFrozen(self);
        }

    }

    @CoreMethod(names = "hash")
    public abstract static class HashNode extends CoreMethodArrayArgumentsNode {

        private static final int MURMUR_SEED = System.identityHashCode(HashNode.class);

        @Specialization
        public long hash(int value) {
            return Hashing.hash(MURMUR_SEED, value);
        }

        @Specialization
        public long hash(long value) {
            return Hashing.hash(MURMUR_SEED, value);
        }

        @Specialization
        public long hash(double value) {
            return Hashing.hash(MURMUR_SEED, Double.doubleToRawLongBits(value));
        }

        @Specialization
        public long hash(boolean value) {
            return Hashing.hash(MURMUR_SEED, Boolean.valueOf(value).hashCode());
        }

        @TruffleBoundary
        @Specialization
        public int hash(DynamicObject self) {
            // TODO(CS 8 Jan 15) we shouldn't use the Java class hierarchy like this - every class should define it's
            // own @CoreMethod hash
            return System.identityHashCode(self);
        }

    }

    @CoreMethod(names = "initialize_copy", required = 1)
    public abstract static class InitializeCopyNode extends CoreMethodArrayArgumentsNode {

        private final BranchProfile errorProfile = BranchProfile.create();

        @Specialization
        public Object initializeCopy(DynamicObject self, DynamicObject from) {
            if (Layouts.BASIC_OBJECT.getLogicalClass(self) != Layouts.BASIC_OBJECT.getLogicalClass(from)) {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().typeError("initialize_copy should take same class object", this));
            }

            return self;
        }

    }

    @CoreMethod(names = { "initialize_dup", "initialize_clone" }, required = 1)
    public abstract static class InitializeDupCloneNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode initializeCopyNode = CallDispatchHeadNode.createOnSelf();

        @Specialization
        public Object initializeDup(VirtualFrame frame, DynamicObject self, DynamicObject from) {
            return initializeCopyNode.call(frame, self, "initialize_copy", from);
        }

    }

    @CoreMethod(names = "instance_of?", required = 1)
    public abstract static class InstanceOfNode extends CoreMethodArrayArgumentsNode {

        @Child private LogicalClassNode classNode = LogicalClassNodeGen.create(null);

        @Specialization(guards = "isRubyModule(rubyClass)")
        public boolean instanceOf(VirtualFrame frame, Object self, DynamicObject rubyClass) {
            return classNode.executeLogicalClass(self) == rubyClass;
        }

    }

    @CoreMethod(names = "instance_variable_defined?", required = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class InstanceVariableDefinedNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization
        public boolean isInstanceVariableDefinedBoolean(boolean object, String name) {
            return false;
        }

        @Specialization
        public boolean isInstanceVariableDefinedInt(int object, String name) {
            return false;
        }

        @Specialization
        public boolean isInstanceVariableDefinedLong(long object, String name) {
            return false;
        }

        @Specialization
        public boolean isInstanceVariableDefinedDouble(double object, String name) {
            return false;
        }

        @Specialization(guards = "isRubySymbol(object) || isNil(object)")
        public boolean isInstanceVariableDefinedSymbolOrNil(DynamicObject object, String name) {
            return false;
        }

        @TruffleBoundary
        @Specialization(guards = {"!isRubySymbol(object)", "!isNil(object)"})
        public boolean isInstanceVariableDefined(DynamicObject object, String name) {
            final String ivar = SymbolTable.checkInstanceVariableName(getContext(), name, object, this);
            final Property property = object.getShape().getProperty(ivar);
            return PropertyFlags.isDefined(property);
        }

    }

    @CoreMethod(names = "instance_variable_get", required = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class InstanceVariableGetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceName(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization
        public Object instanceVariableGetSymbol(DynamicObject object, String name,
                @Cached("createObjectIVarGetNode()") ObjectIVarGetNode iVarGetNode) {
            return iVarGetNode.executeIVarGet(object, name);
        }

        protected ObjectIVarGetNode createObjectIVarGetNode() {
            return ObjectIVarGetNodeGen.create(true, null, null);
        }

    }

    @CoreMethod(names = "instance_variable_set", raiseIfFrozenSelf = true, required = 2)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name"),
            @NodeChild(type = RubyNode.class, value = "value")
    })
    public abstract static class InstanceVariableSetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceName(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization
        public Object instanceVariableSet(DynamicObject object, String name, Object value,
                @Cached("createObjectIVarSetNode()") ObjectIVarSetNode iVarSetNode) {
            return iVarSetNode.executeIVarSet(object, name, value);
        }

        protected ObjectIVarSetNode createObjectIVarSetNode() {
            return ObjectIVarSetNodeGen.create(true, null, null, null);
        }

    }

    @CoreMethod(names = "remove_instance_variable", raiseIfFrozenSelf = true, required = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class RemoveInstanceVariableNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @TruffleBoundary
        @Specialization
        public Object removeInstanceVariable(DynamicObject object, String name) {
            final String ivar = SymbolTable.checkInstanceVariableName(getContext(), name, object, this);
            final Object value = ReadObjectFieldNode.read(object, ivar, nil());

            if (SharedObjects.isShared(getContext(), object)) {
                synchronized (object) {
                    removeField(object, name);
                }
            } else {
                if (!object.delete(name)) {
                    throw new RaiseException(coreExceptions().nameErrorInstanceVariableNotDefined(name, object, this));
                }
            }
            return value;
        }

        private void removeField(DynamicObject object, String name) {
            Shape shape = object.getShape();
            Property property = shape.getProperty(name);
            if (!PropertyFlags.isDefined(property)) {
                throw new RaiseException(coreExceptions().nameErrorInstanceVariableNotDefined(name, object, this));
            }

            Shape newShape = shape.replaceProperty(property, PropertyFlags.asRemoved(property));
            object.setShapeAndGrow(shape, newShape);
        }
    }

    @CoreMethod(names = "instance_variables")
    public abstract static class InstanceVariablesNode extends CoreMethodArrayArgumentsNode {

        @Child private ObjectInstanceVariablesNode instanceVariablesNode = ObjectInstanceVariablesNodeFactory.create(null);

        @Specialization
        public DynamicObject instanceVariables(Object self) {
            return instanceVariablesNode.executeGetIVars(self);
        }

    }

    @CoreMethod(names = { "is_a?", "kind_of?" }, required = 1)
    public abstract static class KernelIsANode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public boolean isA(Object self, DynamicObject module,
                @Cached("create()") IsANode isANode) {
            return isANode.executeIsA(self, module);
        }

        @Specialization(guards = "!isRubyModule(module)")
        public boolean isATypeError(Object self, Object module) {
            throw new RaiseException(coreExceptions().typeError("class or module required", this));
        }

    }

    @CoreMethod(names = "lambda", isModuleFunction = true, needsBlock = true)
    public abstract static class LambdaNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject lambda(NotProvided block) {
            final Frame parentFrame = getContext().getCallStack().getCallerFrameIgnoringSend(0).getFrame(FrameAccess.READ_ONLY);
            final DynamicObject parentBlock = RubyArguments.getBlock(parentFrame);

            if (parentBlock == null) {
                throw new RaiseException(coreExceptions().argumentError("tried to create Proc object without a block", this));
            }

            Node callNode = getContext().getCallStack().getCallerFrameIgnoringSend(1).getCallNode();
            if (isLiteralBlock(callNode)) {
                return lambdaFromBlock(parentBlock);
            } else {
                return parentBlock;
            }
        }

        @Specialization(guards = {"isCloningEnabled()", "isLiteralBlock"})
        public DynamicObject lambdaFromBlockCloning(DynamicObject block,
                        @Cached("isLiteralBlock(block)") boolean isLiteralBlock) {
            return lambdaFromBlock(block);
        }

        @Specialization(guards = {"isCloningEnabled()", "!isLiteralBlock"})
        public DynamicObject lambdaFromExistingProcCloning(DynamicObject block,
                        @Cached("isLiteralBlock(block)") boolean isLiteralBlock) {
            return block;
        }

        @Specialization(guards = "isLiteralBlock(block)")
        public DynamicObject lambdaFromBlock(DynamicObject block) {
            return ProcOperations.createRubyProc(
                            coreLibrary().getProcFactory(),
                            ProcType.LAMBDA,
                            Layouts.PROC.getSharedMethodInfo(block),
                            Layouts.PROC.getCallTargetForLambdas(block),
                            Layouts.PROC.getCallTargetForLambdas(block),
                            Layouts.PROC.getDeclarationFrame(block),
                            Layouts.PROC.getMethod(block),
                            Layouts.PROC.getSelf(block),
                            Layouts.PROC.getBlock(block));
        }

        @Specialization(guards = "!isLiteralBlock(block)")
        public DynamicObject lambdaFromExistingProc(DynamicObject block) {
            return block;
        }

        @TruffleBoundary
        protected boolean isLiteralBlock(DynamicObject block) {
            Node callNode = getContext().getCallStack().getCallerFrameIgnoringSend().getCallNode();
            return isLiteralBlock(callNode);
        }

        private boolean isLiteralBlock(Node callNode) {
            if (callNode.getParent() instanceof DispatchNode) {
                RubyCallNode rubyCallNode = ((DispatchNode) callNode.getParent()).findRubyCallNode();
                if (rubyCallNode != null) {
                    return rubyCallNode.hasLiteralBlock();
                }
            }
            return false;
        }

        protected boolean isCloningEnabled() {
            return coreLibrary().isCloningEnabled();
        }
    }

    @CoreMethod(names = "local_variables", isModuleFunction = true)
    public abstract static class LocalVariablesNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject localVariables() {
            final Frame frame = getContext().getCallStack().getCallerFrameIgnoringSend().getFrame(FrameInstance.FrameAccess.READ_ONLY);
            return BindingNodes.LocalVariablesNode.listLocalVariables(getContext(), frame);
        }

    }

    @CoreMethod(names = "__method__", isModuleFunction = true)
    public abstract static class MethodNameNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject methodName() {
            // the "original/definition name" of the method.
            return getSymbol(getContext().getCallStack().getCallingMethodIgnoringSend().getSharedMethodInfo().getName());
        }

    }

    @CoreMethod(names = "method", required = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class MethodNode extends CoreMethodNode {

        @Child private NameToJavaStringNode nameToJavaStringNode = NameToJavaStringNode.create();
        @Child private LookupMethodNode lookupMethodNode = LookupMethodNodeGen.create(true, false, null, null);
        @Child private CallDispatchHeadNode respondToMissingNode = CallDispatchHeadNode.createOnSelf();

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToSymbolOrStringNodeGen.create(name);
        }

        @Specialization
        public DynamicObject method(VirtualFrame frame, Object self, DynamicObject name,
                @Cached("createBinaryProfile()") ConditionProfile notFoundProfile,
                @Cached("createBinaryProfile()") ConditionProfile respondToMissingProfile) {
            final String normalizedName = nameToJavaStringNode.executeToJavaString(frame, name);
            InternalMethod method = lookupMethodNode.executeLookupMethod(frame, self, normalizedName);

            if (notFoundProfile.profile(method == null)) {
                if (respondToMissingProfile.profile(respondToMissingNode.callBoolean(frame, self, "respond_to_missing?", null, name, true))) {
                    final InternalMethod methodMissing = lookupMethodNode.executeLookupMethod(frame, self, "method_missing").withName(normalizedName);
                    method = createMissingMethod(self, name, normalizedName, methodMissing);
                } else {
                    throw new RaiseException(coreExceptions().nameErrorUndefinedMethod(normalizedName, coreLibrary().getLogicalClass(self), this));
                }
            }

            return Layouts.METHOD.createMethod(coreLibrary().getMethodFactory(), self, method);
        }

        @TruffleBoundary
        private InternalMethod createMissingMethod(Object self, DynamicObject name, String normalizedName, InternalMethod methodMissing) {
            final SharedMethodInfo info = methodMissing.getSharedMethodInfo().withName(normalizedName);

            final RubyNode newBody = new CallMethodMissingWithStaticName(name);
            final RubyRootNode newRootNode = new RubyRootNode(getContext(), info.getSourceSection(), new FrameDescriptor(nil()), info, newBody, false);
            final CallTarget newCallTarget = Truffle.getRuntime().createCallTarget(newRootNode);

            final DynamicObject module = coreLibrary().getMetaClass(self);
            return new InternalMethod(getContext(), info, methodMissing.getLexicalScope(), normalizedName, module, Visibility.PUBLIC, newCallTarget);
        }

        private static class CallMethodMissingWithStaticName extends RubyNode {

            private final DynamicObject methodName;
            @Child private CallDispatchHeadNode methodMissing = CallDispatchHeadNode.create();

            public CallMethodMissingWithStaticName(DynamicObject methodName) {
                this.methodName = methodName;
            }

            @Override
            public Object execute(VirtualFrame frame) {
                final Object[] originalUserArguments = RubyArguments.getArguments(frame);
                final Object[] newUserArguments = ArrayUtils.unshift(originalUserArguments, methodName);
                return methodMissing.callWithBlock(frame, RubyArguments.getSelf(frame), "method_missing", RubyArguments.getBlock(frame), newUserArguments);
            }
        }

    }

    @CoreMethod(names = "methods", optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "regular")
    })
    public abstract static class MethodsNode extends CoreMethodNode {

        @CreateCast("regular")
        public RubyNode coerceToBoolean(RubyNode regular) {
            return BooleanCastWithDefaultNodeGen.create(true, regular);
        }

        @TruffleBoundary
        @Specialization(guards = "regular")
        public DynamicObject methodsRegular(Object self, boolean regular,
                                            @Cached("createMetaClassNode()") MetaClassNode metaClassNode) {
            final DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            Object[] objects = Layouts.MODULE.getFields(metaClass).filterMethodsOnObject(getContext(), regular, MethodFilter.PUBLIC_PROTECTED).toArray();
            return createArray(objects, objects.length);
        }

        @Specialization(guards = "!regular")
        public DynamicObject methodsSingleton(VirtualFrame frame, Object self, boolean regular,
                                              @Cached("createSingletonMethodsNode()") SingletonMethodsNode singletonMethodsNode) {
            return singletonMethodsNode.executeSingletonMethods(frame, self, false);
        }

        protected MetaClassNode createMetaClassNode() {
            return MetaClassNodeGen.create(null);
        }

        protected SingletonMethodsNode createSingletonMethodsNode() {
            return SingletonMethodsNodeFactory.create(null, null);
        }

    }

    @CoreMethod(names = "nil?", needsSelf = false)
    public abstract static class NilNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public boolean isNil() {
            return false;
        }
    }

    // A basic Kernel#p for debugging core, overridden later in kernel.rb
    @NonStandard
    @CoreMethod(names = "p", isModuleFunction = true, required = 1)
    public abstract static class DebugPrintNode extends CoreMethodArrayArgumentsNode {

        @Child private CallDispatchHeadNode callInspectNode = CallDispatchHeadNode.create();

        @Specialization
        public Object p(VirtualFrame frame, Object value) {
            Object inspected = callInspectNode.call(frame, value, "inspect");
            print(inspected);
            return value;
        }

        @TruffleBoundary
        private void print(Object inspected) {
            final PrintStream stream = new PrintStream(getContext().getEnv().out(), true);
            stream.println(inspected.toString());
        }
    }

    @CoreMethod(names = "private_methods", optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "includeAncestors")
    })
    public abstract static class PrivateMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNodeGen.create(null);

        @CreateCast("includeAncestors")
        public RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject privateMethods(Object self, boolean includeAncestors) {
            DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            Object[] objects = Layouts.MODULE.getFields(metaClass).filterMethodsOnObject(getContext(), includeAncestors, MethodFilter.PRIVATE).toArray();
            return createArray(objects, objects.length);
        }

    }

    @CoreMethod(names = "proc", isModuleFunction = true, needsBlock = true)
    public abstract static class ProcNode extends CoreMethodArrayArgumentsNode {

        @Child private ProcNewNode procNewNode = ProcNewNodeFactory.create(null);

        @Specialization
        public DynamicObject proc(VirtualFrame frame, Object maybeBlock) {
            return procNewNode.executeProcNew(frame, coreLibrary().getProcClass(), ArrayUtils.EMPTY_ARRAY, maybeBlock);
        }

    }

    @CoreMethod(names = "protected_methods", optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "includeAncestors")
    })
    public abstract static class ProtectedMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNodeGen.create(null);

        @CreateCast("includeAncestors")
        public RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject protectedMethods(Object self, boolean includeAncestors) {
            final DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            Object[] objects = Layouts.MODULE.getFields(metaClass).filterMethodsOnObject(getContext(), includeAncestors, MethodFilter.PROTECTED).toArray();
            return createArray(objects, objects.length);
        }

    }

    @CoreMethod(names = "public_methods", optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "includeAncestors")
    })
    public abstract static class PublicMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNodeGen.create(null);

        @CreateCast("includeAncestors")
        public RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject publicMethods(Object self, boolean includeAncestors) {
            final DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            Object[] objects = Layouts.MODULE.getFields(metaClass).filterMethodsOnObject(getContext(), includeAncestors, MethodFilter.PUBLIC).toArray();
            return createArray(objects, objects.length);
        }

    }

    @CoreMethod(names = "public_send", needsBlock = true, required = 1, rest = true)
    public abstract static class PublicSendNode extends CoreMethodArrayArgumentsNode {

        @Child private DispatchHeadNode dispatchNode;

        public PublicSendNode() {
            dispatchNode = new DispatchHeadNode(false, true, MissingBehavior.CALL_METHOD_MISSING, DispatchAction.CALL_METHOD);
        }

        @Specialization
        public Object send(VirtualFrame frame, Object self, Object name, Object[] args, NotProvided block) {
            return send(frame, self, name, args, (DynamicObject) null);
        }

        @Specialization
        public Object send(VirtualFrame frame, Object self, Object name, Object[] args, DynamicObject block) {
            return dispatchNode.dispatch(frame, self, name, block, args);
        }

    }

    @CoreMethod(names = "require", isModuleFunction = true, required = 1)
    @NodeChild(type = RubyNode.class, value = "feature")
    public abstract static class KernelRequireNode extends CoreMethodNode {

        @CreateCast("feature")
        public RubyNode coerceFeatureToPath(RubyNode feature) {
            return ToPathNodeGen.create(feature);
        }

        @Specialization(guards = "isRubyString(featureString)")
        public boolean require(VirtualFrame frame, DynamicObject featureString,
                @Cached("create()") RequireNode requireNode) {

            String feature = StringOperations.getString(featureString);

            // Pysch loads either the jar or the so - we need to intercept
            if (feature.equals("psych.so") && callerIs("mri/psych.rb")) {
                feature = "truffle/psych.rb";
            }

            // TODO CS 1-Mar-15 ERB will use strscan if it's there, but strscan is not yet complete, so we need to hide it
            if (feature.equals("strscan") && callerIs("mri/erb.rb")) {
                throw new RaiseException(coreExceptions().loadErrorCannotLoad(feature, this));
            }

            return requireNode.executeRequire(frame, feature);
        }

        @TruffleBoundary
        private boolean callerIs(String caller) {
            for (Activation activation : getContext().getCallStack().getBacktrace(this).getActivations()) {

                final Source source = activation.getCallNode().getEncapsulatingSourceSection().getSource();

                if (source != null && source.getName().endsWith(caller)) {
                    return true;
                }
            }

            return false;
        }
    }

    @CoreMethod(names = "require_relative", isModuleFunction = true, required = 1)
    public abstract static class RequireRelativeNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRubyString(feature)")
        public boolean requireRelative(VirtualFrame frame, DynamicObject feature,
                @Cached("create()") RequireNode requireNode) {
            final String featureString = StringOperations.getString(feature);
            final String featurePath = getFullPath(featureString);

            return requireNode.executeRequire(frame, featurePath);
        }

        @TruffleBoundary
        private String getFullPath(final String featureString) {
            final String featurePath;

            if (new File(featureString).isAbsolute()) {
                featurePath = featureString;
            } else {
                final Source source = getContext().getCallStack().getCallerFrameIgnoringSend().getCallNode().getEncapsulatingSourceSection().getSource();

                String sourcePath = source.getPath();
                if (sourcePath == null) {
                    // Use the filename passed to eval as basepath
                    sourcePath = source.getName();
                }

                if (sourcePath == null) {
                    throw new RaiseException(coreExceptions().loadError("cannot infer basepath", featureString, this));
                }

                featurePath = dirname(sourcePath) + "/" + featureString;
            }

            return featurePath;
        }

        private String dirname(String path) {
            final int lastSlash = path.lastIndexOf(File.separatorChar);
            if (lastSlash == -1) {
                return path;
            } else {
                return path.substring(0, lastSlash);
            }
        }
    }

    @CoreMethod(names = "respond_to?", required = 1, optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name"),
            @NodeChild(type = RubyNode.class, value = "includeProtectedAndPrivate")
    })
    public abstract static class RespondToNode extends CoreMethodNode {

        @Child private DoesRespondDispatchHeadNode dispatch;
        @Child private DoesRespondDispatchHeadNode dispatchIgnoreVisibility;
        @Child private DoesRespondDispatchHeadNode dispatchRespondToMissing;
        @Child private CallDispatchHeadNode respondToMissingNode;
        private final ConditionProfile ignoreVisibilityProfile = ConditionProfile.createBinaryProfile();

        public RespondToNode() {
            dispatch = new DoesRespondDispatchHeadNode(false);
            dispatchIgnoreVisibility = new DoesRespondDispatchHeadNode(true);
            dispatchRespondToMissing = new DoesRespondDispatchHeadNode(true);
        }

        public abstract boolean executeDoesRespondTo(VirtualFrame frame, Object object, Object name, boolean includeProtectedAndPrivate);

        @CreateCast("includeProtectedAndPrivate")
        public RubyNode coerceToBoolean(RubyNode includeProtectedAndPrivate) {
            return BooleanCastWithDefaultNodeGen.create(false, includeProtectedAndPrivate);
        }

        @Specialization(guards = "isRubyString(name)")
        public boolean doesRespondToString(VirtualFrame frame, Object object, DynamicObject name, boolean includeProtectedAndPrivate) {
            final boolean ret;

            if (ignoreVisibilityProfile.profile(includeProtectedAndPrivate)) {
                ret = dispatchIgnoreVisibility.doesRespondTo(frame, name, object);
            } else {
                ret = dispatch.doesRespondTo(frame, name, object);
            }

            if (ret) {
                return true;
            } else if (dispatchRespondToMissing.doesRespondTo(frame, "respond_to_missing?", object)) {
                return respondToMissing(frame, object, getSymbol(StringOperations.rope(name)), includeProtectedAndPrivate);
            } else {
                return false;
            }
        }

        @Specialization(guards = "isRubySymbol(name)")
        public boolean doesRespondToSymbol(VirtualFrame frame, Object object, DynamicObject name, boolean includeProtectedAndPrivate) {
            final boolean ret;

            if (ignoreVisibilityProfile.profile(includeProtectedAndPrivate)) {
                ret = dispatchIgnoreVisibility.doesRespondTo(frame, name, object);
            } else {
                ret = dispatch.doesRespondTo(frame, name, object);
            }

            if (ret) {
                return true;
            } else if (dispatchRespondToMissing.doesRespondTo(frame, "respond_to_missing?", object)) {
                return respondToMissing(frame, object, name, includeProtectedAndPrivate);
            } else {
                return false;
            }
        }

        private boolean respondToMissing(VirtualFrame frame, Object object, DynamicObject name, boolean includeProtectedAndPrivate) {
            if (respondToMissingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                respondToMissingNode = insert(CallDispatchHeadNode.createOnSelf());
            }

            return respondToMissingNode.callBoolean(frame, object, "respond_to_missing?", null, name, includeProtectedAndPrivate);
        }
    }

    @CoreMethod(names = "respond_to_missing?", required = 2)
    public abstract static class RespondToMissingNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isRubyString(name)")
        public boolean doesRespondToMissingString(Object object, DynamicObject name, Object unusedIncludeAll) {
            return false;
        }

        @Specialization(guards = "isRubySymbol(name)")
        public boolean doesRespondToMissingSymbol(Object object, DynamicObject name, Object unusedIncludeAll) {
            return false;
        }

    }

    @CoreMethod(names = "set_trace_func", isModuleFunction = true, required = 1)
    public abstract static class SetTraceFuncNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "isNil(nil)")
        public DynamicObject setTraceFunc(Object nil) {
            getContext().getTraceManager().setTraceFunc(null);
            return nil();
        }

        @Specialization(guards = "isRubyProc(traceFunc)")
        public DynamicObject setTraceFunc(DynamicObject traceFunc) {
            getContext().getTraceManager().setTraceFunc(traceFunc);
            return traceFunc;
        }
    }

    @CoreMethod(names = "singleton_class")
    public abstract static class SingletonClassMethodNode extends CoreMethodArrayArgumentsNode {

        @Child private SingletonClassNode singletonClassNode = SingletonClassNodeGen.create(null);

        @Specialization
        public DynamicObject singletonClass(Object self) {
            return singletonClassNode.executeSingletonClass(self);
        }

    }

    @CoreMethod(names = "singleton_method", required = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class SingletonMethodNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNodeGen.create(null);

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization
        public DynamicObject singletonMethod(Object self, String name,
                @Cached("create()") BranchProfile errorProfile,
                @Cached("createBinaryProfile()") ConditionProfile singletonProfile,
                @Cached("createBinaryProfile()") ConditionProfile methodProfile) {
            final DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            if (singletonProfile.profile(Layouts.CLASS.getIsSingleton(metaClass))) {
                final InternalMethod method = Layouts.MODULE.getFields(metaClass).getMethod(name);
                if (methodProfile.profile(method != null && !method.isUndefined())) {
                    return Layouts.METHOD.createMethod(coreLibrary().getMethodFactory(), self, method);
                }
            }

            errorProfile.enter();
            throw new RaiseException(coreExceptions().nameErrorUndefinedSingletonMethod(name, self, this));
        }

    }

    @CoreMethod(names = "singleton_methods", optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "object"),
            @NodeChild(type = RubyNode.class, value = "includeAncestors")
    })
    public abstract static class SingletonMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNodeGen.create(null);

        public abstract DynamicObject executeSingletonMethods(VirtualFrame frame, Object self, boolean includeAncestors);

        @CreateCast("includeAncestors")
        public RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject singletonMethods(Object self, boolean includeAncestors) {
            final DynamicObject metaClass = metaClassNode.executeMetaClass(self);

            if (!Layouts.CLASS.getIsSingleton(metaClass)) {
                return createArray(null, 0);
            }

            Object[] objects = Layouts.MODULE.getFields(metaClass).filterSingletonMethods(getContext(), includeAncestors, MethodFilter.PUBLIC_PROTECTED).toArray();
            return createArray(objects, objects.length);
        }

    }

    @NodeChild(value = "duration", type = RubyNode.class)
    @CoreMethod(names = "sleep", isModuleFunction = true, optional = 1)
    public abstract static class SleepNode extends CoreMethodNode {

        @CreateCast("duration")
        public RubyNode coerceDuration(RubyNode duration) {
            return DurationToMillisecondsNodeGen.create(false, duration);
        }

        @Specialization
        public long sleep(long duration) {
            return doSleepMillis(duration);
        }

        @TruffleBoundary
        private long doSleepMillis(final long durationInMillis) {
            if (durationInMillis < 0) {
                throw new RaiseException(coreExceptions().argumentError("time interval must be positive", this));
            }

            final DynamicObject thread = getContext().getThreadManager().getCurrentThread();

            // Clear the wakeUp flag, following Ruby semantics:
            // it should only be considered if we are inside the sleep when Thread#{run,wakeup} is called.
            Layouts.THREAD.getWakeUp(thread).set(false);

            return sleepFor(this, getContext(), durationInMillis);
        }

        public static long sleepFor(Node currentNode, RubyContext context, final long durationInMillis) {
            assert durationInMillis >= 0;

            final DynamicObject thread = context.getThreadManager().getCurrentThread();

            final long start = System.currentTimeMillis();

            long slept = context.getThreadManager().runUntilResult(currentNode, () -> {
                long now = System.currentTimeMillis();
                long slept1 = now - start;

                if (slept1 >= durationInMillis || Layouts.THREAD.getWakeUp(thread).getAndSet(false)) {
                    return slept1;
                }
                Thread.sleep(durationInMillis - slept1);

                return System.currentTimeMillis() - start;
            });

            return slept / 1000;
        }

    }

    @CoreMethod(names = { "format", "sprintf" }, isModuleFunction = true, rest = true, required = 1, taintFrom = 1)
    @ImportStatic({ StringCachingGuards.class, StringOperations.class })
    public abstract static class SprintfNode extends CoreMethodArrayArgumentsNode {

        @Child private RopeNodes.MakeLeafRopeNode makeLeafRopeNode;
        @Child private TaintNode taintNode;
        @Child private BooleanCastNode readDebugGlobalNode = BooleanCastNodeGen.create(ReadGlobalVariableNodeGen.create("$DEBUG"));

        private final BranchProfile exceptionProfile = BranchProfile.create();
        private final ConditionProfile resizeProfile = ConditionProfile.createBinaryProfile();

        @Specialization(guards = { "isRubyString(format)", "equalNode.execute(rope(format), cachedFormat)", "isDebug(frame) == cachedIsDebug" })
        public DynamicObject formatCached(
                VirtualFrame frame,
                DynamicObject format,
                Object[] arguments,
                @Cached("isDebug(frame)") boolean cachedIsDebug,
                @Cached("privatizeRope(format)") Rope cachedFormat,
                @Cached("ropeLength(cachedFormat)") int cachedFormatLength,
                @Cached("create(compileFormat(format, arguments, isDebug(frame)))") DirectCallNode callPackNode,
                @Cached("create()") RopeNodes.EqualNode equalNode) {
            final BytesResult result;

            try {
                result = (BytesResult) callPackNode.call(
                        new Object[]{ arguments, arguments.length });
            } catch (FormatException e) {
                exceptionProfile.enter();
                throw FormatExceptionTranslator.translate(this, e);
            }

            return finishFormat(cachedFormatLength, result);
        }

        @Specialization(guards = "isRubyString(format)", replaces = "formatCached")
        public DynamicObject formatUncached(
                VirtualFrame frame,
                DynamicObject format,
                Object[] arguments,
                @Cached("create()") IndirectCallNode callPackNode) {
            final BytesResult result;

            final boolean isDebug = readDebugGlobalNode.executeBoolean(frame);

            try {
                result = (BytesResult) callPackNode.call(compileFormat(format, arguments, isDebug),
                        new Object[]{ arguments, arguments.length });
            } catch (FormatException e) {
                exceptionProfile.enter();
                throw FormatExceptionTranslator.translate(this, e);
            }

            return finishFormat(Layouts.STRING.getRope(format).byteLength(), result);
        }

        private DynamicObject finishFormat(int formatLength, BytesResult result) {
            byte[] bytes = result.getOutput();

            if (resizeProfile.profile(bytes.length != result.getOutputLength())) {
                bytes = Arrays.copyOf(bytes, result.getOutputLength());
            }

            if (makeLeafRopeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeLeafRopeNode = insert(RopeNodes.MakeLeafRopeNode.create());
            }

            final DynamicObject string = createString(makeLeafRopeNode.executeMake(
                    bytes,
                    result.getEncoding().getEncodingForLength(formatLength),
                    result.getStringCodeRange(),
                    result.getOutputLength()));

            if (result.isTainted()) {
                if (taintNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    taintNode = insert(TaintNode.create());
                }

                taintNode.executeTaint(string);
            }

            return string;
        }

        @TruffleBoundary
        protected CallTarget compileFormat(DynamicObject format, Object[] arguments, boolean isDebug) {
            assert RubyGuards.isRubyString(format);

            try {
                return new PrintfCompiler(getContext(), this)
                        .compile(Layouts.STRING.getRope(format).getBytes(), arguments, isDebug);
            } catch (InvalidFormatException e) {
                throw new RaiseException(coreExceptions().argumentError(e.getMessage(), this));
            }
        }

        protected boolean isDebug(VirtualFrame frame) {
            return readDebugGlobalNode.executeBoolean(frame);
        }

    }

    @Primitive(name = "kernel_global_variables", needsSelf = false)
    public abstract static class KernelGlobalVariablesPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject globalVariables() {
            final Collection<String> keys = coreLibrary().getGlobalVariables().keys();
            final Object[] store = new Object[keys.size()];
            int i = 0;
            for (String key : keys) {
                store[i] = getSymbol(key);
                i++;
            }
            return createArray(store, store.length);
        }

    }

    @CoreMethod(names = "taint")
    public abstract static class KernelTaintNode extends CoreMethodArrayArgumentsNode {

        @Child private TaintNode taintNode;

        @Specialization
        public Object taint(Object object) {
            if (taintNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                taintNode = insert(TaintNode.create());
            }
            return taintNode.executeTaint(object);
        }

    }

    @CoreMethod(names = "tainted?")
    public abstract static class KernelIsTaintedNode extends CoreMethodArrayArgumentsNode {

        @Child private IsTaintedNode isTaintedNode;

        @Specialization
        public boolean isTainted(Object object) {
            if (isTaintedNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isTaintedNode = insert(IsTaintedNode.create());
            }
            return isTaintedNode.executeIsTainted(object);
        }

    }

    public abstract static class ToHexStringNode extends CoreMethodArrayArgumentsNode {

        public abstract String executeToHexString(Object value);

        @Specialization
        public String toHexString(int value) {
            return toHexString((long) value);
        }

        @Specialization
        public String toHexString(long value) {
            return Long.toHexString(value);
        }

        @Specialization(guards = "isRubyBignum(value)")
        public String toHexString(DynamicObject value) {
            return Layouts.BIGNUM.getValue(value).toString(16);
        }

    }

    @CoreMethod(names = {"to_s", "inspect"}) // Basic inspect, refined later in core
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {

        @Child private LogicalClassNode classNode = LogicalClassNodeGen.create(null);
        @Child private ObjectIDNode objectIDNode = ObjectIDNodeFactory.create(null);
        @Child private TaintResultNode taintResultNode = new TaintResultNode();
        @Child private ToHexStringNode toHexStringNode = ToHexStringNodeFactory.create(null);

        public abstract DynamicObject executeToS(Object self);

        @Specialization
        public DynamicObject toS(Object self) {
            String className = Layouts.MODULE.getFields(classNode.executeLogicalClass(self)).getName();
            Object id = objectIDNode.executeObjectID(self);
            String hexID = toHexStringNode.executeToHexString(id);

            final DynamicObject string = createString(formatToS(className, hexID));
            taintResultNode.maybeTaint(self, string);
            return string;
        }

        @TruffleBoundary
        private Rope formatToS(String className, String hexID) {
            return StringOperations.encodeRope("#<" + className + ":0x" + hexID + ">", UTF8Encoding.INSTANCE);
        }

    }

    @CoreMethod(names = "untaint")
    public abstract static class UntaintNode extends CoreMethodArrayArgumentsNode {

        @Child private IsFrozenNode isFrozenNode;
        @Child private IsTaintedNode isTaintedNode = IsTaintedNode.create();
        @Child private WriteObjectFieldNode writeTaintNode = WriteObjectFieldNodeGen.create(Layouts.TAINTED_IDENTIFIER);

        @Specialization
        public int untaint(int num) {
            return num;
        }

        @Specialization
        public long untaint(long num) {
            return num;
        }

        @Specialization
        public double untaint(double num) {
            return num;
        }

        @Specialization
        public boolean untaint(boolean bool) {
            return bool;
        }

        @Specialization
        public Object taint(DynamicObject object) {
            if (!isTaintedNode.executeIsTainted(object)) {
                return object;
            }

            checkFrozen(object);
            writeTaintNode.execute(object, false);
            return object;
        }

        protected void checkFrozen(Object object) {
            if (isFrozenNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isFrozenNode = insert(IsFrozenNodeGen.create(null));
            }
            isFrozenNode.raiseIfFrozen(object);
        }

    }

}
