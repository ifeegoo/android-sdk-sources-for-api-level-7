/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2009 Eric Lafortune (eric@graphics.cornell.edu)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.optimize.peephole;

import proguard.classfile.*;
import proguard.classfile.constant.visitor.*;
import proguard.classfile.editor.*;
import proguard.classfile.util.*;
import proguard.classfile.visitor.*;
import proguard.optimize.KeepMarker;
import proguard.optimize.info.*;
import proguard.util.*;

import java.util.*;

/**
 * This ClassVisitor inlines the classes that it visits in a given target class,
 * whenever possible.
 *
 * @see RetargetedInnerClassAttributeRemover
 * @see TargetClassChanger
 * @see ClassReferenceFixer
 * @see MemberReferenceFixer
 * @see AccessFixer
 * @author Eric Lafortune
 */
public class ClassMerger
extends      SimplifiedVisitor
implements   ClassVisitor,
             ConstantVisitor
{
    //*
    private static final boolean DEBUG = false;
    /*/
    private static       boolean DEBUG = true;
    //*/


    private final ProgramClass targetClass;
    private final boolean      allowAccessModification;
    private final boolean      mergeInterfacesAggressively;
    private final ClassVisitor extraClassVisitor;


    /**
     * Creates a new ClassMerger that will merge classes into the given target
     * class.
     * @param targetClass                 the class into which all visited
     *                                    classes will be merged.
     * @param allowAccessModification     specifies whether the access modifiers
     *                                    of classes can be changed in order to
     *                                    merge them.
     * @param mergeInterfacesAggressively specifies whether interfaces may
     *                                    be merged aggressively.
     */
    public ClassMerger(ProgramClass targetClass,
                       boolean      allowAccessModification,
                       boolean      mergeInterfacesAggressively)
    {
        this(targetClass, allowAccessModification, mergeInterfacesAggressively, null);
    }


    /**
     * Creates a new ClassMerger that will merge classes into the given target
     * class.
     * @param targetClass                 the class into which all visited
     *                                    classes will be merged.
     * @param allowAccessModification     specifies whether the access modifiers
     *                                    of classes can be changed in order to
     *                                    merge them.
     * @param mergeInterfacesAggressively specifies whether interfaces may
     *                                    be merged aggressively.
     * @param extraClassVisitor           an optional extra visitor for all
     *                                    merged classes.
     */
    public ClassMerger(ProgramClass targetClass,
                       boolean      allowAccessModification,
                       boolean      mergeInterfacesAggressively,
                       ClassVisitor extraClassVisitor)
    {
        this.targetClass                 = targetClass;
        this.allowAccessModification     = allowAccessModification;
        this.mergeInterfacesAggressively = mergeInterfacesAggressively;
        this.extraClassVisitor           = extraClassVisitor;
    }


    // Implementations for ClassVisitor.

    public void visitProgramClass(ProgramClass programClass)
    {
        //final String CLASS_NAME = "abc/Def";
        //DEBUG = programClass.getName().equals(CLASS_NAME) ||
        //        targetClass.getName().equals(CLASS_NAME);

        // TODO: Remove this when the class merger has stabilized.
        // Catch any unexpected exceptions from the actual visiting method.
        try
        {
            visitProgramClass0(programClass);
        }
        catch (RuntimeException ex)
        {
            System.err.println("Unexpected error while merging classes:");
            System.err.println("  Class        = ["+programClass.getName()+"]");
            System.err.println("  Target class = ["+targetClass.getName()+"]");
            System.err.println("  Exception    = ["+ex.getClass().getName()+"] ("+ex.getMessage()+")");

            if (DEBUG)
            {
                programClass.accept(new ClassPrinter());
                targetClass.accept(new ClassPrinter());
            }

            throw ex;
        }
    }

    public void visitProgramClass0(ProgramClass programClass)
    {
        if (!programClass.equals(targetClass) &&

            // Don't merge classes that must be preserved.
            !KeepMarker.isKept(programClass) &&
            !KeepMarker.isKept(targetClass)  &&

            // Only merge classes that haven't been retargeted yet.
            getTargetClass(programClass) == null &&
            getTargetClass(targetClass)  == null &&

            // Don't merge annotation classes, with all their introspection and
            // infinite recursion.
            (programClass.getAccessFlags() & ClassConstants.INTERNAL_ACC_ANNOTATTION) == 0 &&

            // Only merge classes if we can change the access permissioms, or
            // if they are in the same package, or
            // if they are public and don't contain or invoke package visible
            // class members.
            (allowAccessModification                                                        ||
             ((programClass.getAccessFlags() &
               targetClass.getAccessFlags()  &
               ClassConstants.INTERNAL_ACC_PUBLIC) != 0 &&
              !PackageVisibleMemberContainingClassMarker.containsPackageVisibleMembers(programClass) &&
              !PackageVisibleMemberInvokingClassMarker.invokesPackageVisibleMembers(programClass)) ||
             ClassUtil.internalPackageName(programClass.getName()).equals(
             ClassUtil.internalPackageName(targetClass.getName()))) &&

            // Only merge two classes or two interfaces or two abstract classes,
            // or a class into an interface with a single implementation.
            ((programClass.getAccessFlags() &
              (ClassConstants.INTERNAL_ACC_INTERFACE |
               ClassConstants.INTERNAL_ACC_ABSTRACT)) ==
             (targetClass.getAccessFlags()  &
              (ClassConstants.INTERNAL_ACC_INTERFACE |
               ClassConstants.INTERNAL_ACC_ABSTRACT)) ||
             (isOnlySubClass(programClass, targetClass) &&
              (programClass.getSuperClass().equals(targetClass) ||
               programClass.getSuperClass().equals(targetClass.getSuperClass())))) &&

            // One class must not implement the other class indirectly.
            !indirectlyImplementedInterfaces(programClass).contains(targetClass) &&
            !targetClass.extendsOrImplements(programClass) &&

            // The two classes must have the same superclasses and interfaces
            // with static initializers.
            initializedSuperClasses(programClass).equals(initializedSuperClasses(targetClass))   &&

            // The two classes must have the same superclasses and interfaces
            // that are tested with 'instanceof'.
            instanceofedSuperClasses(programClass).equals(instanceofedSuperClasses(targetClass)) &&

            // The two classes must have the same superclasses that are caught
            // as exceptions.
            caughtSuperClasses(programClass).equals(caughtSuperClasses(targetClass)) &&

            // The two classes must not both be part of a .class construct.
            !(DotClassMarker.isDotClassed(programClass) &&
              DotClassMarker.isDotClassed(targetClass)) &&

            // The two classes must not introduce any unwanted fields.
            !introducesUnwantedFields(programClass, targetClass) &&
            !introducesUnwantedFields(targetClass, programClass) &&

            // The classes must not have clashing constructors.
            !haveAnyIdenticalInitializers(programClass, targetClass) &&

            // The classes must not introduce abstract methods, unless
            // explicitly allowed.
            (mergeInterfacesAggressively ||
             (!introducesUnwantedAbstractMethods(programClass, targetClass) &&
              !introducesUnwantedAbstractMethods(targetClass, programClass))) &&

            // The classes must not override each others concrete methods.
            !overridesAnyMethods(programClass, targetClass) &&
            !overridesAnyMethods(targetClass, programClass) &&

            // The classes must not shadow each others non-private methods.
            !shadowsAnyMethods(programClass, targetClass) &&
            !shadowsAnyMethods(targetClass, programClass))
        {
            if (DEBUG)
            {
                System.out.println("ClassMerger ["+programClass.getName()+"] -> ["+targetClass.getName()+"]");
                System.out.println("  Source interface? ["+((programClass.getAccessFlags() & ClassConstants.INTERNAL_ACC_INTERFACE)!=0)+"]");
                System.out.println("  Target interface? ["+((targetClass.getAccessFlags() & ClassConstants.INTERNAL_ACC_INTERFACE)!=0)+"]");
                System.out.println("  Source subclasses ["+programClass.subClasses+"]");
                System.out.println("  Target subclasses ["+targetClass.subClasses+"]");
                System.out.println("  Source superclass ["+programClass.getSuperClass().getName()+"]");
                System.out.println("  Target superclass ["+targetClass.getSuperClass().getName()+"]");
            }

            // Combine the access flags.
            int targetAccessFlags = targetClass.getAccessFlags();
            int sourceAccessFlags = programClass.getAccessFlags();

            targetClass.u2accessFlags =
                ((targetAccessFlags &
                  sourceAccessFlags) &
                 (ClassConstants.INTERNAL_ACC_INTERFACE  |
                  ClassConstants.INTERNAL_ACC_ABSTRACT)) |
                ((targetAccessFlags |
                  sourceAccessFlags) &
                 (ClassConstants.INTERNAL_ACC_PUBLIC     |
                  ClassConstants.INTERNAL_ACC_ANNOTATTION |
                  ClassConstants.INTERNAL_ACC_ENUM));

            // Copy over the superclass, unless it's the target class itself.
            //if (!targetClass.getName().equals(programClass.getSuperName()))
            //{
            //    targetClass.u2superClass =
            //        new ConstantAdder(targetClass).addConstant(programClass, programClass.u2superClass);
            //}

            // Copy over the interfaces that aren't present yet and that
            // wouldn't cause loops in the class hierarchy.
            programClass.interfaceConstantsAccept(
                new ExceptClassConstantFilter(targetClass.getName(),
                new ImplementedClassConstantFilter(targetClass,
                new ImplementingClassConstantFilter(targetClass,
                new InterfaceAdder(targetClass)))));

            // Copy over the class members.
            MemberAdder memberAdder =
                new MemberAdder(targetClass);

            programClass.fieldsAccept(memberAdder);
            programClass.methodsAccept(memberAdder);

            // Copy over the other attributes.
            programClass.attributesAccept(
                new AttributeAdder(targetClass, true));

            // Update the optimization information of the target class.
            ClassOptimizationInfo info =
                ClassOptimizationInfo.getClassOptimizationInfo(targetClass);
            if (info != null)
            {
                info.merge(ClassOptimizationInfo.getClassOptimizationInfo(programClass));
            }

            // Remember to replace the inlined class by the target class.
            setTargetClass(programClass, targetClass);

            // Visit the merged class, if required.
            if (extraClassVisitor != null)
            {
                extraClassVisitor.visitProgramClass(programClass);
            }
        }
    }


    // Small utility methods.

    /**
     * Returns whether a given class is the only subclass of another given class.
     */
    private boolean isOnlySubClass(Clazz        subClass,
                                   ProgramClass clazz)
    {
        // TODO: The list of subclasses is not up to date.
        return clazz.subClasses != null     &&
               clazz.subClasses.length == 1 &&
               clazz.subClasses[0].equals(subClass);
    }


    /**
     * Returns the set of indirectly implemented interfaces.
     */
    private Set indirectlyImplementedInterfaces(Clazz clazz)
    {
        Set set = new HashSet();

        ReferencedClassVisitor referencedInterfaceCollector =
            new ReferencedClassVisitor(
            new ClassHierarchyTraveler(false, false, true, false,
            new ClassCollector(set)));

        // Visit all superclasses and  collect their interfaces.
        clazz.superClassConstantAccept(referencedInterfaceCollector);

        // Visit all interfaces and collect their interfaces.
        clazz.interfaceConstantsAccept(referencedInterfaceCollector);

        return set;
    }


    /**
     * Returns the set of superclasses and interfaces that are initialized.
     */
    private Set initializedSuperClasses(Clazz clazz)
    {
        Set set = new HashSet();

        // Visit all superclasses and interfaces, collecting the ones that have
        // static initializers.
        clazz.hierarchyAccept(true, true, true, false,
                              new NamedMethodVisitor(ClassConstants.INTERNAL_METHOD_NAME_CLINIT,
                                                     ClassConstants.INTERNAL_METHOD_TYPE_INIT,
                              new MemberToClassVisitor(
                              new ClassCollector(set))));

        return set;
    }


    /**
     * Returns the set of superclasses and interfaces that are used in
     * 'instanceof' tests.
     */
    private Set instanceofedSuperClasses(Clazz clazz)
    {
        Set set = new HashSet();

        // Visit all superclasses and interfaces, collecting the ones that are
        // used in an 'instanceof' test.
        clazz.hierarchyAccept(true, true, true, false,
                              new InstanceofClassFilter(
                              new ClassCollector(set)));

        return set;
    }


    /**
     * Returns the set of superclasses that are caught as exceptions.
     */
    private Set caughtSuperClasses(Clazz clazz)
    {
        Set set = new HashSet();

        // Visit all superclasses, collecting the ones that are caught.
        clazz.hierarchyAccept(true, true, false, false,
                              new CaughtClassFilter(
                              new ClassCollector(set)));

        return set;
    }


    /**
     * Returns whether the given class would introduce any unwanted fields
     * in the target class.
     */
    private boolean introducesUnwantedFields(ProgramClass programClass,
                                             ProgramClass targetClass)
    {
        // The class must not have any fields, or it must not be instantiated,
        // without any other subclasses.
        return
            programClass.u2fieldsCount != 0 &&
            (InstantiationClassMarker.isInstantiated(targetClass) ||
             (targetClass.subClasses != null &&
              !isOnlySubClass(programClass, targetClass)));
    }


    /**
     * Returns whether the two given classes have initializers with the same
     * descriptors.
     */
    private boolean haveAnyIdenticalInitializers(Clazz clazz, Clazz targetClass)
    {
        MemberCounter counter = new MemberCounter();

        // TODO: Currently checking shared methods, not just initializers.
        // TODO: Allow identical methods.
        // Visit all methods, counting the ones that are also present in the
        // target class.
        clazz.methodsAccept(//new MemberNameFilter(new FixedStringMatcher(ClassConstants.INTERNAL_METHOD_NAME_INIT),
                            new SimilarMemberVisitor(targetClass, true, false, false, false,
                            new MemberAccessFilter(0, ClassConstants.INTERNAL_ACC_ABSTRACT,
                            counter)));

        return counter.getCount() > 0;
    }


    /**
     * Returns whether the given class would introduce any abstract methods
     * in the target class.
     */
    private boolean introducesUnwantedAbstractMethods(Clazz        clazz,
                                                      ProgramClass targetClass)
    {
        // It's ok if the target class is already abstract and it has at most
        // the class as a subclass.
        if ((targetClass.getAccessFlags() &
             (ClassConstants.INTERNAL_ACC_ABSTRACT |
              ClassConstants.INTERNAL_ACC_INTERFACE)) != 0 &&
            (targetClass.subClasses == null ||
             isOnlySubClass(clazz, targetClass)))
        {
            return false;
        }

        MemberCounter counter   = new MemberCounter();
        Set           targetSet = new HashSet();

        // Collect all abstract methods, and similar abstract methods in the
        // class hierarchy of the target class.
        clazz.methodsAccept(new MemberAccessFilter(ClassConstants.INTERNAL_ACC_ABSTRACT, 0,
                            new MultiMemberVisitor(new MemberVisitor[]
                            {
                                counter,
                                new SimilarMemberVisitor(targetClass, true, true, true, false,
                                                         new MemberAccessFilter(ClassConstants.INTERNAL_ACC_ABSTRACT, 0,
                                                         new MemberCollector(targetSet)))
                            })));

        return targetSet.size() < counter.getCount();
    }


    /**
     * Returns whether the given class overrides any methods in the given
     * target class.
     */
    private boolean overridesAnyMethods(Clazz clazz, Clazz targetClass)
    {
        MemberCounter counter = new MemberCounter();

        // Visit all non-private non-static methods, counting the ones that are
        // being overridden in the class hierarchy of the target class.
        clazz.methodsAccept(new MemberAccessFilter(0, ClassConstants.INTERNAL_ACC_PRIVATE | ClassConstants.INTERNAL_ACC_STATIC | ClassConstants.INTERNAL_ACC_ABSTRACT,
                            new MemberNameFilter(new NotMatcher(new FixedStringMatcher(ClassConstants.INTERNAL_METHOD_NAME_CLINIT)),
                            new MemberNameFilter(new NotMatcher(new FixedStringMatcher(ClassConstants.INTERNAL_METHOD_NAME_INIT)),
                            new SimilarMemberVisitor(targetClass, true, true, false, false,
                            new MemberAccessFilter(0, ClassConstants.INTERNAL_ACC_PRIVATE | ClassConstants.INTERNAL_ACC_STATIC | ClassConstants.INTERNAL_ACC_ABSTRACT,
                            counter))))));

        return counter.getCount() > 0;
    }


    /**
     * Returns whether the given class or its subclasses shadow any methods in
     * the given target class.
     */
    private boolean shadowsAnyMethods(Clazz clazz, Clazz targetClass)
    {
        MemberCounter counter = new MemberCounter();

        // Visit all private methods, counting the ones that are shadowing
        // non-private methods in the class hierarchy of the target class.
        clazz.hierarchyAccept(true, false, false, true,
                              new AllMethodVisitor(
                              new MemberAccessFilter(ClassConstants.INTERNAL_ACC_PRIVATE, 0,
                              new MemberNameFilter(new NotMatcher(new FixedStringMatcher(ClassConstants.INTERNAL_METHOD_NAME_INIT)),
                              new SimilarMemberVisitor(targetClass, true, true, true, false,
                              new MemberAccessFilter(0, ClassConstants.INTERNAL_ACC_PRIVATE,
                              counter))))));

        // Visit all static methods, counting the ones that are shadowing
        // non-private methods in the class hierarchy of the target class.
        clazz.hierarchyAccept(true, false, false, true,
                              new AllMethodVisitor(
                              new MemberAccessFilter(ClassConstants.INTERNAL_ACC_STATIC, 0,
                              new MemberNameFilter(new NotMatcher(new FixedStringMatcher(ClassConstants.INTERNAL_METHOD_NAME_CLINIT)),
                              new SimilarMemberVisitor(targetClass, true, true, true, false,
                              new MemberAccessFilter(0, ClassConstants.INTERNAL_ACC_PRIVATE,
                              counter))))));

        return counter.getCount() > 0;
    }


    public static void setTargetClass(Clazz clazz, Clazz targetClass)
    {
        ClassOptimizationInfo info = ClassOptimizationInfo.getClassOptimizationInfo(clazz);
        if (info != null)
        {
            info.setTargetClass(targetClass);
        }
    }


    public static Clazz getTargetClass(Clazz clazz)
    {
        Clazz targetClass = null;

        // Return the last target class, if any.
        while (true)
        {
            ClassOptimizationInfo info = ClassOptimizationInfo.getClassOptimizationInfo(clazz);
            if (info == null)
            {
                return targetClass;
            }

            clazz = info.getTargetClass();
            if (clazz == null)
            {
                return targetClass;
            }

            targetClass = clazz;
        }
    }
}