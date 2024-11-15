/*
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.lsp.type

import java.util.*
import org.pkl.lsp.PklBaseModule
import org.pkl.lsp.Project
import org.pkl.lsp.ast.*
import org.pkl.lsp.documentation.DefaultTypeNameRenderer
import org.pkl.lsp.documentation.TypeNameRenderer
import org.pkl.lsp.packages.dto.PklProject
import org.pkl.lsp.resolvers.ResolveVisitor
import org.pkl.lsp.unexpectedType

/**
 * A type whose names have been resolved to their definitions.
 *
 * [nullable] types are represented as [Union] types. Function types are represented as the
 * corresponding [Class] types (but [render]ed as function types).
 *
 * [equals] is defined as *structural* equality and equivalence of referenced PklNodes; constraints
 * are not taken into account. To test for type equivalence, use [isEquivalentTo].
 */
sealed class Type(val constraints: List<ConstraintExpr> = listOf()) {
  companion object {
    fun alias(
      node: PklTypeAlias,
      context: PklProject?,
      specifiedTypeArguments: List<Type> = listOf(),
      constraints: List<ConstraintExpr> = listOf(),
    ): Type =
      // Note: this is incomplete in that it doesn't detect the case
      // where recursion is introduced via type argument:
      // typealias Alias<T> = T|Boolean
      // p: Alias<Alias<String>>
      if (node.isRecursive(context)) Unknown
      else Alias.unchecked(node, specifiedTypeArguments, constraints)

    fun module(ctx: PklModule, referenceName: String, context: PklProject?): Module =
      Module.create(ctx, referenceName, context)

    fun union(type1: Type, type2: Type, base: PklBaseModule, context: PklProject?): Type =
      Union.create(type1, type2, base, context)

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Type = Union.create(Union.create(type1, type2, base, context), type3, base, context)

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Type =
      Union.create(
        Union.create(Union.create(type1, type2, base, context), type3, base, context),
        type4,
        base,
        context,
      )

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Type =
      Union.create(
        Union.create(
          Union.create(Union.create(type1, type2, base, context), type3, base, context),
          type4,
          base,
          context,
        ),
        type5,
        base,
        context,
      )

    fun union(
      type1: Type,
      type2: Type,
      type3: Type,
      type4: Type,
      type5: Type,
      type6: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Type =
      Union.create(
        Union.create(
          Union.create(
            Union.create(Union.create(type1, type2, base, context), type3, base, context),
            type4,
            base,
            context,
          ),
          type5,
          base,
          context,
        ),
        type6,
        base,
        context,
      )

    fun union(types: List<Type>, base: PklBaseModule, context: PklProject?): Type =
      types.reduce { t1, t2 -> Union.create(t1, t2, base, context) }

    fun function1(param1Type: Type, returnType: Type, base: PklBaseModule): Type =
      base.function1Type.withTypeArguments(param1Type, returnType)
  }

  open val hasConstraints: Boolean = constraints.isNotEmpty()

  abstract fun withConstraints(constraints: List<ConstraintExpr>): Type

  abstract fun visitMembers(
    isProperty: Boolean,
    allowClasses: Boolean,
    base: PklBaseModule,
    visitor: ResolveVisitor<*>,
    context: PklProject?,
  ): Boolean

  abstract fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement>

  /** Tells whether this type is a (non-strict) subtype of [classType]. */
  abstract fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean

  /** Tells whether this type is a (non-strict) subtype of [type]. */
  abstract fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean

  fun hasDefault(base: PklBaseModule, context: PklProject?) =
    if (isNullable(base, context)) true else hasDefaultImpl(base, context)

  protected abstract fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean

  /** Helper for implementing [isSubtypeOf]. */
  protected fun doIsSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
    when (type) {
      Unknown -> true
      is Class -> isSubtypeOf(type, base, context)
      is Alias -> isSubtypeOf(type.aliasedType(base, context), base, context)
      is Union ->
        isSubtypeOf(type.leftType, base, context) || isSubtypeOf(type.rightType, base, context)
      else -> false
    }

  /** Note that `unknown` is equivalent to every type. */
  fun isEquivalentTo(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
    isSubtypeOf(type, base, context) && type.isSubtypeOf(this, base, context)

  /**
   * Tells if there is a refinement of this type that is a subtype of [type]. The trivial
   * refinements `nothing` and `unkown` are not considered valid answers. Assumes
   * `!isSubtypeOf(type)`.
   *
   * The motivation for this method is to check if `!isSubtypeOf(type)` could be caused by the type
   * system being too weak, which is only the case if `hasCommonSubtypeWith(type)`.
   */
  abstract fun hasCommonSubtypeWith(type: Type, base: PklBaseModule, context: PklProject?): Boolean

  /** Helper for implementing [hasCommonSubtypeWith]. */
  protected fun doHasCommonSubtypeWith(
    type: Type,
    base: PklBaseModule,
    context: PklProject?,
  ): Boolean =
    when (type) {
      is Alias -> hasCommonSubtypeWith(type.aliasedType(base, context), base, context)
      is Union ->
        hasCommonSubtypeWith(type.leftType, base, context) ||
          hasCommonSubtypeWith(type.rightType, base, context)
      else -> true
    }

  /**
   * Tells whether an unresolved member should be reported as error (rather than warning) for this
   * type.
   *
   * Implementations should return `false` if there is a chance that the member is declared by a
   * subtype.
   */
  abstract fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean

  open fun toClassType(base: PklBaseModule, context: PklProject?): Class? = null

  open fun unaliased(base: PklBaseModule, context: PklProject?): Type? = this

  open fun nonNull(base: PklBaseModule, context: PklProject?): Type =
    if (this == base.nullType) Nothing else this

  fun nullable(base: PklBaseModule, context: PklProject?): Type =
    // Foo? is syntactic sugar for Null|Foo
    // Null|Foo and Foo|Null are equivalent for typing purposes but have different defaults
    union(base.nullType, this, base, context)

  open val bindings: TypeParameterBindings = mapOf()

  fun isNullable(base: PklBaseModule, context: PklProject?): Boolean =
    base.nullType.isSubtypeOf(this, base, context)

  fun isAmendable(base: PklBaseModule, context: PklProject?): Boolean =
    amended(base, context) != Nothing

  fun isInstantiable(base: PklBaseModule, context: PklProject?): Boolean =
    instantiated(base, context) != Nothing

  /**
   * The type of `expr {}` where `expr` has this type. Defaults to [Nothing], that is, not
   * amendable.
   */
  open fun amended(base: PklBaseModule, context: PklProject?): Type = Nothing

  /**
   * The type of `new T {}` where `T` is this type. (Assumption: `T` is exactly the instantiated
   * type, not a supertype.) Defaults to [Nothing], that is, not instantiable.
   */
  open fun instantiated(base: PklBaseModule, context: PklProject?): Type = amended(base, context)

  /**
   * Type inside an amend block whose parent has this type. Leniently defaults to [Unknown] (instead
   * of [Nothing]) because "cannot amend type" is reported separately.
   */
  open fun amending(base: PklBaseModule, context: PklProject?): Type = Unknown

  abstract fun render(builder: Appendable, nameRenderer: TypeNameRenderer = DefaultTypeNameRenderer)

  fun render(): String = buildString { render(this) }

  override fun toString(): String = render()

  fun getNode(project: Project, context: PklProject?): PklNode? =
    when (this) {
      is Class -> ctx
      is Module -> ctx
      is StringLiteral -> project.pklBaseModule.stringType.getNode(project, context)
      is Alias -> ctx
      is Union -> null
      else -> null
    }

  object Unknown : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean = true

    // Note: we aren't currently tracking constraints for unknown type (uncommon, would require a
    // class)
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      // Ideally we'd return "`unknown` with upper bound `base.amendedType`",
      // but this cannot currently be expressed
      Unknown

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      // Ideally we'd return "`unknown` with upper bound `Object`",
      // but this cannot currently be expressed.
      Unknown

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("unknown")
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      true

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf()

    // `unknown` is not considered a valid answer
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = false

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false
  }

  object Nothing : Type() {
    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean = true

    // constraints for bottom type aren't meaningful -> don't track them
    override fun withConstraints(constraints: List<ConstraintExpr>): Type = this

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf()

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      true

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean = true

    // `nothing` is not considered a valid answer
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append("nothing")
    }
  }

  class Variable(val ctx: PklTypeParameter, constraints: List<ConstraintExpr> = listOf()) :
    Type(constraints) {

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Variable(ctx, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(ctx)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      classType.classEquals(base.anyType)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      this == type || doIsSubtypeOf(type, base, context)

    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean = type.unaliased(base, context) != Nothing

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      true // treat like `Any`

    override fun amended(base: PklBaseModule, context: PklProject?): Type = this

    override fun amending(base: PklBaseModule, context: PklProject?): Type = Unknown

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = false

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      builder.append(ctx.name)
    }

    override fun toString(): String = ctx.name
  }

  class Module
  private constructor(
    val ctx: PklModule,
    val referenceName: String,
    constraints: List<ConstraintExpr>,
  ) : Type(constraints) {
    companion object {
      // this method exists because `Type.module()` can't see the private constructor
      internal fun create(
        ctx: PklModule,
        referenceName: String,
        context: PklProject?,
        constraints: List<ConstraintExpr> = listOf(),
      ): Module {
        var result = ctx
        // a module's type is the topmost module in the module hierarchy that doesn't amend another
        // module.
        // if we can't resolve an amends reference, we bail out, i.e., invalid code may produce an
        // incorrect type.
        while (result.isAmend) {
          result = result.supermodule(context) ?: return Module(result, referenceName, constraints)
        }
        return Module(result, referenceName, constraints)
      }
    }

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Module(ctx, referenceName, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean {
      return if (allowClasses) {
        ctx.cache(context).visitTypeDefsAndPropertiesOrMethods(isProperty, visitor, context)
      } else {
        ctx.cache(context).visitPropertiesOrMethods(isProperty, visitor, context)
      }
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(ctx)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      base.moduleType.isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is Module -> isSubtypeOf(type, context)
        else -> doIsSubtypeOf(type, base, context)
      }

    private fun isSubtypeOf(type: Module, context: PklProject?): Boolean {
      var currCtx: PklModule? = ctx
      while (currCtx != null) {
        // TODO: check if this actually works
        if (currCtx == type.ctx) return true
        currCtx = currCtx.supermodule(context)
      }
      return false
    }

    fun supermodule(context: PklProject?): Module? =
      ctx.supermodule(context)?.let { module(it, it.shortDisplayName, context) }

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean =
      when (type) {
        is Module -> type.isSubtypeOf(this, context)
        is Class -> type.isSubtypeOf(this, base, context)
        else -> doHasCommonSubtypeWith(type, base, context)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      !ctx.isAbstractOrOpen

    override fun amended(base: PklBaseModule, context: PklProject?): Type = this

    override fun amending(base: PklBaseModule, context: PklProject?): Type = this

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      nameRenderer.render(this, builder)
    }
  }

  class Class(
    val ctx: PklClass,
    specifiedTypeArguments: List<Type> = listOf(),
    constraints: List<ConstraintExpr> = listOf(),
    // enables the illusion that pkl.base#Class and pkl.base#TypeAlias
    // have a type parameter even though they currently don't
    private val typeParameters: List<PklTypeParameter> =
      ctx.typeParameterList?.typeParameters ?: listOf(),
  ) : Type(constraints) {
    val typeArguments: List<Type> =
      when {
        typeParameters.size <= specifiedTypeArguments.size ->
          specifiedTypeArguments.take(typeParameters.size)
        else ->
          specifiedTypeArguments +
            List(typeParameters.size - specifiedTypeArguments.size) { Unknown }
      }

    override val bindings: TypeParameterBindings = typeParameters.zip(typeArguments).toMap()

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Class(ctx, typeArguments, constraints, typeParameters)

    fun withTypeArguments(argument1: Type) =
      Class(ctx, listOf(argument1), constraints, typeParameters)

    fun withTypeArguments(argument1: Type, argument2: Type) =
      Class(ctx, listOf(argument1, argument2), constraints, typeParameters)

    fun withTypeArguments(arguments: List<Type>) =
      Class(ctx, arguments, constraints, typeParameters)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean {
      ctx.cache(context).visitPropertiesOrMethods(isProperty, bindings, visitor, context)
      return true
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(ctx)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean {
      // optimization
      if (classType.ctx === base.anyType.ctx) return true

      if (!ctx.isSubclassOf(classType.ctx, context)) return false

      if (typeArguments.isEmpty()) {
        assert(classType.typeArguments.isEmpty()) // holds for stdlib
        return true
      }

      val size = typeArguments.size
      val otherSize = classType.typeArguments.size
      assert(size >= otherSize) // holds for stdlib

      for (i in 1..otherSize) {
        // assume [typeArg] maps directly to [otherTypeArg] in extends clause(s) (holds for stdlib)
        val typeArg = typeArguments[size - i]
        val typeParam = typeParameters[size - i]
        val otherTypeArg = classType.typeArguments[otherSize - i]
        val isMatch =
          when (typeParam.variance) {
            Variance.OUT -> typeArg.isSubtypeOf(otherTypeArg, base, context) // covariance
            Variance.IN -> otherTypeArg.isSubtypeOf(typeArg, base, context) // contravariance
            else -> typeArg.isEquivalentTo(otherTypeArg, base, context) // invariance
          }
        if (!isMatch) return false
      }
      return true
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is Module -> ctx.isSubclassOf(type.ctx, context)
        else -> doIsSubtypeOf(type, base, context)
      }

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      !ctx.isAbstractOrOpen

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean =
      when (type) {
        is Class -> hasCommonSubtypeWith(type, base, context)
        is Module -> type.isSubtypeOf(this, base, context)
        else -> doHasCommonSubtypeWith(type, base, context)
      }

    val isNullType: Boolean by lazy { ctx.name == "Null" && ctx.isInPklBaseModule }

    val isFunctionType: Boolean by lazy {
      val name = ctx.name
      (name.length == 8 || name.length == 9 && name.last() in '0'..'5') &&
        name.startsWith("Function") &&
        ctx.isInPklBaseModule
    }

    val isConcreteFunctionType: Boolean by lazy {
      val name = ctx.name
      name.length == 9 &&
        name.last() in '0'..'5' &&
        name.startsWith("Function") &&
        ctx.isInPklBaseModule
    }

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      when {
        classEquals(base.classType) -> typeArguments[0].amended(base, context)
        isFunctionType -> this
        isSubtypeOf(base.objectType, base, context) -> this
        else -> Nothing
      }

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type =
      when {
        ctx.isExternal -> Nothing
        ctx.isAbstract -> Nothing
        else -> this
      }

    override fun amending(base: PklBaseModule, context: PklProject?): Type {
      return when {
        isSubtypeOf(base.objectType, base, context) -> this
        classEquals(base.classType) -> typeArguments[0].amending(base, context)
        isFunctionType -> uncurriedResultType(base, context).amending(base, context)
        else -> {
          // Return `Unknown` instead of `Nothing` to avoid consecutive errors
          // inside an erroneous amend expression's object body.
          // Ideally we'd return "`unknown` with upper bound `Object`",
          // but this cannot currently be expressed.
          Unknown
        }
      }
    }

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class = this

    fun classEquals(other: Class): Boolean =
      // TODO: check if this works
      ctx == other.ctx

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      when (base.objectType) {
        is Class -> isSubtypeOf(base.objectType, base, context) && !ctx.isAbstract
        else -> false
      }

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      if (isConcreteFunctionType) {
        for ((index, type) in typeArguments.withIndex()) {
          when {
            index == 0 && typeArguments.lastIndex == 0 -> builder.append("() -> ")
            index == 0 -> builder.append('(')
            index == typeArguments.lastIndex -> builder.append(") -> ")
            else -> builder.append(", ")
          }
          type.render(builder, nameRenderer)
        }
        return
      }

      nameRenderer.render(this, builder)
      if (typeArguments.any { it != Unknown }) {
        builder.append('<')
        var first = true
        for (arg in typeArguments) {
          if (first) first = false else builder.append(", ")
          arg.render(builder, nameRenderer)
        }
        builder.append('>')
      }
    }

    // returns `C` given `(A) -> (B) -> C`
    private fun uncurriedResultType(base: PklBaseModule, context: PklProject?): Type {
      assert(isFunctionType)

      var type = typeArguments.last()
      var classType = type.toClassType(base, context)
      while (classType != null && classType.isFunctionType) {
        type = classType.typeArguments.last()
        classType = type.toClassType(base, context)
      }
      return type
    }

    override fun equals(other: Any?): Boolean {
      val clazz = other as? Class ?: return false
      return ctx === clazz.ctx
    }
  }

  // from a typing perspective, type aliases are transparent, but from a tooling/abstraction
  // perspective, they aren't.
  // this raises questions such as how to define Object.equals() and whether/how to support other
  // forms of equality.
  class Alias
  private constructor(
    val ctx: PklTypeAlias,
    specifiedTypeArguments: List<Type>,
    constraints: List<ConstraintExpr>,
  ) : Type(constraints) {
    companion object {
      /** Use [Type.alias] instead except in [PklBaseModule]. */
      internal fun unchecked(
        ctx: PklTypeAlias,
        specifiedTypeArguments: List<Type>,
        constraints: List<ConstraintExpr>,
      ): Alias = Alias(ctx, specifiedTypeArguments, constraints)
    }

    private val typeParameters: List<PklTypeParameter>
      get() = ctx.typeParameterList?.typeParameters ?: listOf()

    val typeArguments: List<Type> =
      when {
        typeParameters.size <= specifiedTypeArguments.size ->
          specifiedTypeArguments.take(typeParameters.size)
        else ->
          specifiedTypeArguments +
            List(typeParameters.size - specifiedTypeArguments.size) { Unknown }
      }

    fun withTypeArguments(argument1: Type) = Alias(ctx, listOf(argument1), constraints)

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Alias(ctx, typeArguments, constraints)

    override val bindings: TypeParameterBindings = typeParameters.zip(typeArguments).toMap()

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean {
      // return ctx.body.toType(base, bindings).visitMembers(isProperty, allowClasses, base,
      // visitor)
      return true
    }

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> = listOf(ctx)

    fun aliasedType(base: PklBaseModule, context: PklProject?): Type =
      ctx.type.toType(base, bindings, context)

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isSubtypeOf(type, base, context)

    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean = aliasedType(base, context).hasCommonSubtypeWith(type, base, context)

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).isUnresolvedMemberFatal(base, context)

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class? =
      unaliased(base, context) as? Class

    override fun nonNull(base: PklBaseModule, context: PklProject?): Type {
      val aliasedType = aliasedType(base, context)
      return if (aliasedType.isNullable(base, context)) aliasedType.nonNull(base, context) else this
    }

    override fun unaliased(base: PklBaseModule, context: PklProject?): Type {
      var type: Type = this
      // guard against (invalid) cyclic type alias definition
      val seen = IdentityHashMap<PklTypeAlias, PklTypeAlias>()
      while (type is Alias) {
        val typeCtx = type.ctx
        // returning `type` here could cause infinite recursion in caller
        if (seen.put(typeCtx, typeCtx) != null) return Unknown
        type = typeCtx.type.toType(base, type.bindings, context)
      }
      return type
    }

    override fun amended(base: PklBaseModule, context: PklProject?): Type {
      val aliased = aliasedType(base, context)
      val amended = aliased.amended(base, context)
      return if (aliased == amended) this else amended // keep alias if possible
    }

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type {
      // special case: `Mixin` is instantiable even though `Function1` isn't
      // TODO: check if this works
      if (ctx == base.mixinType.ctx) return this

      val aliased = aliasedType(base, context)
      val instantiated = aliased.instantiated(base, context)
      return if (aliased == instantiated) this else instantiated // keep alias if possible
    }

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      aliasedType(base, context).amending(base, context)

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      aliasedType(base, context).hasDefaultImpl(base, context)

    @Suppress("Duplicates")
    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      nameRenderer.render(this, builder)
      if (typeArguments.any { it != Unknown }) {
        builder.append('<')
        var first = true
        for (arg in typeArguments) {
          if (first) first = false else builder.append(", ")
          arg.render(builder, nameRenderer)
        }
        builder.append('>')
      }
    }
  }

  class StringLiteral(val value: String, constraints: List<ConstraintExpr> = listOf()) :
    Type(constraints) {
    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      StringLiteral(value, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean {
      return base.stringType.visitMembers(isProperty, allowClasses, base, visitor, context)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean {
      return classType.classEquals(base.stringType) || classType.classEquals(base.anyType)
    }

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      when (type) {
        is StringLiteral -> value == type.value
        else -> doIsSubtypeOf(type, base, context)
      }

    // assumes `!isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean = false

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> =
      listOf(base.stringType.ctx)

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean = true

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) = render(builder, "\"")

    fun render(builder: Appendable, startDelimiter: String) {
      builder.append(startDelimiter).append(value).append(startDelimiter.reversed())
    }

    fun render(startDelimiter: String) = buildString { render(this, startDelimiter) }

    override fun toString(): String = "\"$value\""
  }

  class Union
  private constructor(val leftType: Type, val rightType: Type, constraints: List<ConstraintExpr>) :
    Type(constraints) {
    companion object {
      // this method exists because `Type.union(t1, t2)` can't see the private constructor
      internal fun create(
        leftType: Type,
        rightType: Type,
        base: PklBaseModule,
        context: PklProject?,
      ): Type {
        val atMostOneTypeHasConstraints = !leftType.hasConstraints || !rightType.hasConstraints
        return when {
          // Only normalize if we don't lose relevant constraints in the process.
          // Note that if `a` is a subtype of `b` and `b` has no constraints, `a`'s constraints are
          // irrelevant.
          // Also don't normalize `String|"stringLiteral"` because we need the string literal type
          // for code completion.
          atMostOneTypeHasConstraints &&
            leftType.isSubtypeOf(rightType, base, context) &&
            rightType.unaliased(base, context) != base.stringType -> {
            rightType
          }
          atMostOneTypeHasConstraints &&
            rightType.isSubtypeOf(leftType, base, context) &&
            leftType.unaliased(base, context) != base.stringType -> {
            leftType
          }
          else -> Union(leftType, rightType, listOf())
        }
      }
    }

    override fun withConstraints(constraints: List<ConstraintExpr>): Type =
      Union(leftType, rightType, constraints)

    override fun visitMembers(
      isProperty: Boolean,
      allowClasses: Boolean,
      base: PklBaseModule,
      visitor: ResolveVisitor<*>,
      context: PklProject?,
    ): Boolean {
      if (isUnionOfStringLiterals) {
        // visit pkl.base#String once rather than for every string literal
        // (unions of 70+ string literals have been seen in the wild)
        return base.stringType.visitMembers(isProperty, allowClasses, base, visitor, context)
      }

      return leftType.visitMembers(isProperty, allowClasses, base, visitor, context) &&
        rightType.visitMembers(isProperty, allowClasses, base, visitor, context)
    }

    override fun isSubtypeOf(classType: Class, base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isSubtypeOf(classType, base, context) &&
        rightType.isSubtypeOf(classType, base, context)

    override fun isSubtypeOf(type: Type, base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isSubtypeOf(type, base, context) && rightType.isSubtypeOf(type, base, context)

    // assumes `!this.isSubtypeOf(type)`
    override fun hasCommonSubtypeWith(
      type: Type,
      base: PklBaseModule,
      context: PklProject?,
    ): Boolean =
      leftType.isSubtypeOf(type, base, context) ||
        leftType.hasCommonSubtypeWith(type, base, context) ||
        rightType.isSubtypeOf(type, base, context) ||
        rightType.hasCommonSubtypeWith(type, base, context)

    override fun isUnresolvedMemberFatal(base: PklBaseModule, context: PklProject?): Boolean =
      leftType.isUnresolvedMemberFatal(base, context) &&
        rightType.isUnresolvedMemberFatal(base, context)

    override fun toClassType(base: PklBaseModule, context: PklProject?): Class? =
      if (leftType.hasConstraints && rightType.hasConstraints) {
        // Ensure that `toClassType(CT(c1)|CT(c2)|CT(c3))`,
        // whose argument isn't normalized due to different constraints,
        // returns `CT`.
        leftType.toClassType(base, context)?.let { leftClassType ->
          rightType.toClassType(base, context)?.let { rightClassType ->
            if (leftClassType.classEquals(rightClassType)) leftClassType else null
          }
        }
      } else null

    override fun resolveToDefinitions(base: PklBaseModule): List<PklNavigableElement> =
      when {
        isUnionOfStringLiterals -> listOf(base.stringType.ctx)
        else -> leftType.resolveToDefinitions(base) + rightType.resolveToDefinitions(base)
      }

    override fun nonNull(base: PklBaseModule, context: PklProject?): Type =
      when {
        leftType == base.nullType -> rightType.nonNull(base, context)
        rightType == base.nullType -> leftType.nonNull(base, context)
        else ->
          create(leftType.nonNull(base, context), rightType.nonNull(base, context), base, context)
            .withConstraints(constraints)
      }

    override fun amended(base: PklBaseModule, context: PklProject?): Type =
      create(leftType.amended(base, context), rightType.amended(base, context), base, context)
        .withConstraints(constraints)

    override fun instantiated(base: PklBaseModule, context: PklProject?): Type =
      create(
        leftType.instantiated(base, context),
        rightType.instantiated(base, context),
        base,
        context,
      )

    override fun amending(base: PklBaseModule, context: PklProject?): Type =
      when {
        // assume this type is amendable (checked separately)
        // and remove alternatives that can't
        !leftType.isAmendable(base, context) -> rightType.amending(base, context)
        !rightType.isAmendable(base, context) -> leftType.amending(base, context)
        else ->
          create(leftType.amending(base, context), rightType.amending(base, context), base, context)
            .withConstraints(constraints)
      }

    override fun render(builder: Appendable, nameRenderer: TypeNameRenderer) {
      if (leftType is Class && leftType.isNullType) {
        val addParens = rightType is Union || rightType is Class && rightType.isConcreteFunctionType
        if (addParens) builder.append('(')
        rightType.render(builder, nameRenderer)
        if (addParens) builder.append(')')
        builder.append('?')
        return
      }

      leftType.render(builder, nameRenderer)
      builder.append('|')
      rightType.render(builder, nameRenderer)
    }

    fun eachElementType(processor: (Type) -> Unit) {
      if (leftType is Union) leftType.eachElementType(processor) else processor(leftType)
      if (rightType is Union) rightType.eachElementType(processor) else processor(rightType)
    }

    val isUnionOfStringLiterals: Boolean by lazy {
      (leftType is StringLiteral || leftType is Union && leftType.isUnionOfStringLiterals) &&
        (rightType is StringLiteral || rightType is Union && rightType.isUnionOfStringLiterals)
    }

    override fun hasDefaultImpl(base: PklBaseModule, context: PklProject?): Boolean =
      leftType.hasDefaultImpl(base, context)
  }
}

typealias TypeParameterBindings = Map<PklTypeParameter, Type>

fun PklType?.toType(
  base: PklBaseModule,
  bindings: Map<PklTypeParameter, Type>,
  context: PklProject?,
  preserveUnboundTypeVars: Boolean = false,
): Type =
  when (this) {
    null -> Type.Unknown
    is PklDeclaredType -> {
      val simpleName = name.simpleTypeName
      when (val resolved = simpleName.resolve(context)) {
        null -> Type.Unknown
        is PklModule -> Type.module(resolved, simpleName.identifier!!.text, context)
        is PklClass -> {
          val typeArguments = this.typeArgumentList?.types ?: listOf()
          Type.Class(
            resolved,
            typeArguments.toTypes(base, bindings, context, preserveUnboundTypeVars),
          )
        }
        is PklTypeAlias -> {
          val typeArguments = this.typeArgumentList?.types ?: listOf()
          Type.alias(
            resolved,
            context,
            typeArguments.toTypes(base, bindings, context, preserveUnboundTypeVars),
          )
        }
        is PklTypeParameter ->
          bindings[resolved]
            ?: if (preserveUnboundTypeVars) Type.Variable(resolved) else Type.Unknown
        else -> unexpectedType(resolved)
      }
    }
    is PklUnionType ->
      Type.union(
        leftType.toType(base, bindings, context, preserveUnboundTypeVars),
        rightType.toType(base, bindings, context, preserveUnboundTypeVars),
        base,
        context,
      )
    is PklFunctionType -> {
      val parameterTypes = parameterList.toTypes(base, bindings, context, preserveUnboundTypeVars)
      val returnType = returnType.toType(base, bindings, context, preserveUnboundTypeVars)
      when (parameterTypes.size) {
        0 -> base.function0Type.withTypeArguments(parameterTypes + returnType)
        1 -> base.function1Type.withTypeArguments(parameterTypes + returnType)
        2 -> base.function2Type.withTypeArguments(parameterTypes + returnType)
        3 -> base.function3Type.withTypeArguments(parameterTypes + returnType)
        4 -> base.function4Type.withTypeArguments(parameterTypes + returnType)
        5 -> base.function5Type.withTypeArguments(parameterTypes + returnType)
        else ->
          base.functionType.withTypeArguments(
            listOf(returnType)
          ) // approximation (invalid Pkl code)
      }
    }
    is PklParenthesizedType -> type.toType(base, bindings, context, preserveUnboundTypeVars)
    is PklDefaultUnionType -> type.toType(base, bindings, context, preserveUnboundTypeVars)
    is PklConstrainedType -> {
      // TODO: cache `constraintExprs`
      val constraintExprs = exprs.toConstraintExprs(project.pklBaseModule, context)
      type.toType(base, bindings, context, preserveUnboundTypeVars).withConstraints(constraintExprs)
    }
    is PklNullableType ->
      type.toType(base, bindings, context, preserveUnboundTypeVars).nullable(base, context)
    is PklUnknownType -> Type.Unknown
    is PklNothingType -> Type.Nothing
    is PklModuleType -> {
      // TODO: for `open` modules, `module` is a self-type
      enclosingModule?.let { Type.module(it, "module", context) } ?: base.moduleType
    }
    is PklStringLiteralType ->
      stringConstant.escapedText()?.let { Type.StringLiteral(it) } ?: Type.Unknown
    is PklTypeParameter ->
      bindings[this] ?: if (preserveUnboundTypeVars) Type.Variable(this) else Type.Unknown
  }

fun List<PklType>.toTypes(
  base: PklBaseModule,
  bindings: Map<PklTypeParameter, Type>,
  context: PklProject?,
  preserveTypeVariables: Boolean = false,
): List<Type> = map { it.toType(base, bindings, context, preserveTypeVariables) }
