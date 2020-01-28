package edu.berkeley.cs.rise.bcdsl

object Solidity {
  private val SOLIDITY_VERSION: String = "0.5.7"

  private val INDENTATION_STR: String = "    "
  private val CURRENT_STATE_VAR: String = "__currentState"
  private val PARAM_HASH_PLACEHOLDER = "0"
  private val RESERVED_NAME_TRANSLATIONS: Map[String, String] = Map[String, String](
    "balance" -> "balance",
    "now" -> "now",
    "sender" -> "msg.sender",
    "tokens" -> "int(msg.value)",
  )

  private val BUILTIN_PARAMS: Set[String] = Set("tokens")

  private var indentationLevel: Integer = 0

  private def appendLine(builder: StringBuilder, line: String): Unit =
    builder.append(s"${INDENTATION_STR * indentationLevel}$line\n")

  private def writeLine(line: String): String = s"${INDENTATION_STR * indentationLevel}$line\n"

  private def writeType(ty: DataType, payable: Boolean): String =
    ty match {
      case Identity => if (payable) "address payable" else "address"
      case Int => "int"
      case String => "bytes32"
      case Timestamp => "uint"
      case Bool => "bool"
      case Timespan => "uint"
      case Mapping(keyType, valueType) => s"mapping(${writeType(keyType, payable)} => ${writeType(valueType, payable)})"
      case Sequence(elementType) => s"${writeType(elementType, payable)}[]"
    }

  private def writeField(field: Variable, payable: Boolean): String =
    s"${writeType(field.ty, payable)} public ${field.name}"

  private def writeExpression(expression: Expression): String = {
    val builder = new StringBuilder()
    expression match {
      case VarRef(name) => builder.append(RESERVED_NAME_TRANSLATIONS.getOrElse(name, name))
      case MappingRef(map, key) => builder.append(s"${writeExpression(map)}[${writeExpression(key)}]")
      case IntConst(v) => builder.append(v)
      case StringLiteral(s) => builder.append("\"" + s + "\"")
      case BoolConst(b) => builder.append(b)
      case Second => builder.append("seconds")
      case Minute => builder.append("minutes")
      case Hour => builder.append("hours")
      case Day => builder.append("days")
      case Week => builder.append("weeks")

      case ArithmeticOperation(left, operator, right) =>
        left match {
          case LogicalOperation(_, _, _) => builder.append(s"(${writeExpression(left)})")
          case ArithmeticOperation(_, _, _) => builder.append(s"(${writeExpression(left)})")
          case _ => builder.append(writeExpression(left))
        }

        operator match {
          case Plus => builder.append(" + ")
          case Minus => builder.append(" - ")
          case Multiply => right match {
            case Second | Minute | Hour | Day | Week => builder.append(" ")
            case _ => builder.append(" * ")
          }
          case Divide => builder.append(" / ")
        }

        right match {
          case LogicalOperation(_, _, _) => builder.append(s"(${writeExpression(right)})")
          case ArithmeticOperation(_, _, _) => builder.append(s"(${writeExpression(right)})")
          case _ => builder.append(writeExpression(right))
        }

      case LogicalOperation(element, In, sequence) =>
        builder.append(s"sequenceContains(${writeExpression(sequence)}, ${writeExpression(element)})")

      case LogicalOperation(element, NotIn, sequence) =>
        builder.append(s"!(sequenceContains(${writeExpression(sequence)}, ${writeExpression(element)}))")

      case LogicalOperation(left, operator, right) =>
        left match {
          case LogicalOperation(_, _, _) => builder.append(s"(${writeExpression(left)})")
          case ArithmeticOperation(_, _, _) => builder.append(s"(${writeExpression(left)})")
          case _ => builder.append(writeExpression(left))
        }

        operator match {
          case LessThan => builder.append(" < ")
          case LessThanOrEqual => builder.append(" <= ")
          case Equal => builder.append(" == ")
          case NotEqual => builder.append(" != ")
          case GreaterThanOrEqual => builder.append(" >= ")
          case GreaterThan => builder.append(" > ")
          case And => builder.append(" && ")
          case Or => builder.append(" || ")
          case In | NotIn => throw new IllegalArgumentException // This should never be reached
        }

        right match {
          case LogicalOperation(_, _, _) => builder.append(s"(${writeExpression(right)})")
          case ArithmeticOperation(_, _, _) => builder.append(s"(${writeExpression(right)})")
          case _ => builder.append(writeExpression(right))
        }

      case SequenceSize(sequence) => builder.append(s"${writeExpression(sequence)}.length")
    }

    builder.toString()
  }

  private def writeParameter(p: Variable, payable: Boolean): String = s"${writeType(p.ty, payable)} ${p.name}"

  private def writeParameters(parameters: Seq[(Variable, Boolean)]): String =
    parameters.map { case (param, payable) => writeParameter(param, payable) }.mkString(", ")

  private def writeAssignable(assignable: Assignable): String = assignable match {
    case VarRef(name) => name
    case MappingRef(mapName, key) => s"${writeExpression(mapName)}[${writeExpression(key)}]"
  }

  private def writeStatement(statement: Statement): String = statement match {
    case Assignment(left, right) => writeLine(s"${writeAssignable(left)} = ${writeExpression(right)};")

    case Send(destination, amount, source) =>
      val destStr = destination match {
        case ArithmeticOperation(_, _, _) => s"(${writeExpression(destination)})"
        case LogicalOperation(_, _, _) => s"(${writeExpression(destination)})"
        case _ => writeExpression(destination)
      }
      source match {
        // TODO we just convert to uint as needed for now, but this assumes amount >= 0
        case None => writeLine(s"$destStr.transfer(uint(${writeExpression(amount)}));")
        case Some(s) =>
          val builder = new StringBuilder()
          appendLine(builder, s"int __temporary = ${writeExpression(amount)};")
          appendLine(builder, s"${writeAssignable(s)} = ${writeAssignable(s)} - __temporary;")
          appendLine(builder, s"$destStr.transfer(uint(__temporary));")
          builder.toString()
      }

    case SequenceAppend(sequence, element) =>
      writeLine(s"${writeExpression(sequence)}.push(${writeExpression(element)});")

    case SequenceClear(sequence) =>
      writeLine(s"delete ${writeExpression(sequence)};")
  }

  private def writeTransition(transition: Transition, autoTransitions: Map[String, Seq[Transition]]): String = {
    val builder = new StringBuilder()

    val paramsRepr = transition.parameters.fold("") { params =>
      // Remove parameters that are used in the original source but are built in to Solidity
      val effectiveParams = params.filter(p => !BUILTIN_PARAMS.contains(p.name))
      val payableParams = extractPayableVars(transition.body.getOrElse(Seq.empty[Statement]), effectiveParams.map(_.name).toSet)
      writeParameters(effectiveParams.zip(effectiveParams.map(p => payableParams.contains(p.name))))
    }

    val payable = if (transition.parameters.getOrElse(Seq.empty[Variable]).exists(_.name == "tokens")) {
      "payable "
    } else {
      ""
    }

    if (transition.origin.isDefined) {
      appendLine(builder, s"function ${transition.name}($paramsRepr) public $payable{")
    } else {
      appendLine(builder, s"constructor($paramsRepr) public $payable{")
    }
    indentationLevel += 1

    transition.origin.foreach { o =>
      appendLine(builder, s"require($CURRENT_STATE_VAR == State.$o);")
    }

    // These transitions are all distinct from the current transition, but we need to interpose them
    val outgoingAutoTransitions = transition.origin.flatMap(autoTransitions.get)
    outgoingAutoTransitions.foreach(_.filter(_ != transition).zipWithIndex.foreach { case (t, idx) =>
      val g = t.guard.get // Auto transitions must have a guard
      if (idx == 0) {
        appendLine(builder, s"if (${writeExpression(g)}) {")
      } else {
        appendLine(builder, s"else if (${writeExpression(g)}) {")
      }
      indentationLevel += 1

      if (t.destination != t.origin.get) {
        appendLine(builder, s"$CURRENT_STATE_VAR = State.${t.destination};")
      }
      t.body.foreach(_.foreach(s => builder.append(writeStatement(s))))

      appendLine(builder, "return;")
      indentationLevel -= 1
      appendLine(builder, "}")
    })

    transition.guard.foreach { g =>
      appendLine(builder, s"require(${writeExpression(g)});")
    }

    transition.authorized.foreach { authTerm =>
      val subTerms = authTerm.flatten
      if (subTerms.size == 1) {
        // Authorization clause is just a single term, which simplifies things
        // No need for persistent bookkeeping except for an "all" term
        subTerms.head match {
          case IdentityLiteral(identity) =>
            appendLine(builder, s"if (${RESERVED_NAME_TRANSLATIONS("sender")} != $identity) {")
          case AuthAny(collectionName) =>
            appendLine(builder, s"if (!sequenceContains($collectionName, ${RESERVED_NAME_TRANSLATIONS("sender")})) {")
          case AuthAll(collectionName) =>
            appendLine(builder, s"${writeApprovalVarRef(transition, subTerms.head)} = true;")
            val varName = writeApprovalVarName(transition, subTerms.head)
            transition.parameters.fold {
              appendLine(builder, s"if (!allApproved($collectionName, $varName, $PARAM_HASH_PLACEHOLDER)) {")
            } { params =>
              appendLine(builder, s"if (!allApproved($collectionName, $varName, ${writeParamHash(params)})) {")
            }
        }
        indentationLevel += 1
        appendLine(builder, "return;")
        indentationLevel -= 1
        appendLine(builder, "}")
      } else {
        subTerms.zipWithIndex.foreach { case (subTerm, i) =>
          val conditional = if (i == 0) "if" else "else if"
          subTerm match {
            case IdentityLiteral(identity) =>
              appendLine(builder, s"$conditional (${RESERVED_NAME_TRANSLATIONS("sender")} == $identity) {")
              indentationLevel += 1
              appendLine(builder, s"${writeApprovalVarRef(transition, subTerm)} = true;")
              indentationLevel -= 1
              appendLine(builder, "}")

            case AuthAny(collectionName) =>
              appendLine(builder, s"$conditional (sequenceContains($collectionName, ${RESERVED_NAME_TRANSLATIONS("sender")}) {")
              indentationLevel += 1
              appendLine(builder, s"${writeApprovalVarRef(transition, subTerm)} = true;")
              indentationLevel -= 1
              appendLine(builder, "}")

            case AuthAll(collectionName) =>
              appendLine(builder, s"$conditional sequenceContains($collectionName, ${RESERVED_NAME_TRANSLATIONS("sender")}) {")
              indentationLevel += 1
              appendLine(builder, s"${writeApprovalVarRef(transition, subTerm)} = true;")
              indentationLevel -= 1
              appendLine(builder, "}")
          }
        }
        appendLine(builder, s"if (!(${writeAuthClause(transition, authTerm)})) {")
        indentationLevel += 1
        appendLine(builder, "return;")
        indentationLevel -= 1
        appendLine(builder, "}")
      }
    }

    if (transition.origin.getOrElse("") != transition.destination) {
      // We don't need this for a self-loop
      appendLine(builder, s"$CURRENT_STATE_VAR = State.${transition.destination};")
    }

    transition.body.foreach(_.foreach(s => builder.append(writeStatement(s))))

    if (transition.origin.fold(false)(_ == transition.destination)) {
      builder.append(writeClearAuthTerms(transition))
    }
    indentationLevel -= 1
    appendLine(builder, "}")
    builder.append("\n")
    builder.toString()
  }

  private def writeAuthorizationFields(machine: StateMachine): String = {
    val builder = new StringBuilder()

    machine.transitions.foreach(trans => trans.authorized.foreach { authClause =>
      val terms = authClause.flatten
      if (terms.size == 1) {
        terms.head match {
          // We don't need an explicit variable to track this
          case IdentityLiteral(_) | AuthAny(_) => ()
          case AuthAll(_) =>
            appendLine(builder, s"${writeApprovalVarType(trans, terms.head)} private ${writeApprovalVarName(trans, terms.head)};")
        }
      } else {
        terms.foreach { term =>
          appendLine(builder, s"${writeApprovalVarType(trans, term)} private ${writeApprovalVarName(trans, term)};")
        }
      }
    })
    builder.toString()
  }

  private def writeApprovalVarName(transition: Transition, term: AuthTerm): String =
  // Validation ensures that transition must have an origin
  // Only non-initial transitions can have authorization restrictions
    s"__${transition.name}_${term.getReferencedName}Approved"

  private def writeApprovalVarType(transition: Transition, term: AuthTerm): String =
  // Use a hash of the parameters to record approvals specific to parameter combinations
    if (transition.parameters.isEmpty) {
      term match {
        case IdentityLiteral(_) | AuthAny(_) => "bool"
        case AuthAll(_) => "mapping(address => bool)"
      }
    } else {
      term match {
        case IdentityLiteral(_) | AuthAny(_) => "mapping(bytes32 => bool)"
        case AuthAll(_) => "mapping(bytes32 => mapping(address => bool))"
      }
    }

  private def writeApprovalVarRef(transition: Transition, term: AuthTerm): String =
    transition.parameters.fold {
      term match {
        case IdentityLiteral(_) | AuthAny(_) => writeApprovalVarName(transition, term)
        case AuthAll(_) => writeApprovalVarName(transition, term) + s"[${RESERVED_NAME_TRANSLATIONS("sender")}]"
      }
    } { params =>
      val paramHashRepr = writeParamHash(params)
      term match {
        case IdentityLiteral(_) | AuthAny(_) => writeApprovalVarName(transition, term) + s"[$paramHashRepr]"
        case AuthAll(_) => writeApprovalVarName(transition, term) + s"[$paramHashRepr][${RESERVED_NAME_TRANSLATIONS("sender")}]"
      }
    }

  private def writeAuthClause(transition: Transition, term: AuthExpression, depth: Int = 0): String = {
    val builder = new StringBuilder()
    term match {
      case t: AuthTerm => t match {
        case IdentityLiteral(_) | AuthAny(_) => builder.append(writeApprovalVarRef(transition, t))

        case AuthAll(collectionName) =>
          // Strip out last mapping reference so we can look at all identities
          val senderName = RESERVED_NAME_TRANSLATIONS("sender")
          val varName = writeApprovalVarName(transition, t)
          transition.parameters.fold {
            builder.append(s"allApproved($collectionName, $varName)")
          } { params =>
            builder.append(s"allApproved($collectionName, $varName, ${writeParamHash(params)}")
          }
      }

      case AuthCombination(left, operator, right) =>
        if (depth > 0) {
          builder.append("(")
        }
        builder.append(writeAuthClause(transition, left, depth + 1))
        if (depth > 0) {
          builder.append(")")
        }

        operator match {
          case And => builder.append(" && ")
          case Or => builder.append(" || ")
        }

        if (depth > 0) {
          builder.append("(")
        }
        builder.append(writeAuthClause(transition, right, depth + 1))
        if (depth > 0) {
          builder.append(")")
        }
    }
    builder.toString()
  }

  private def writeClearAuthTerms(transition: Transition): String = {
    val authTerms = transition.authorized.fold(Set.empty[AuthTerm])(_.flatten)
    if (authTerms.size == 1) {
      authTerms.head match {
        case term@AuthAll(_) => writeClearAuthTerm(transition, term)
        case _ => ""
      }
    } else {
      val builder = new StringBuilder()
      authTerms.foreach(term => builder.append(writeClearAuthTerm(transition, term)))
      builder.toString()
    }
  }

  private def writeClearAuthTerm(transition: Transition, term: AuthTerm): String = term match {
    case IdentityLiteral(_) | AuthAny(_) =>
      writeLine(s"${writeApprovalVarRef(transition, term)} = false;")
    case AuthAll(collectionName) =>
      val varName = writeApprovalVarRef(transition, term).dropRight(s"[${RESERVED_NAME_TRANSLATIONS("sender")}]".length)
      s"""
         |for (uint i = 0; i < $collectionName.length; i++) {
         |    $varName[$collectionName[i]] = false;
         |}
      """.trim.stripMargin.split("\n").map(INDENTATION_STR * indentationLevel + _).mkString("\n") + "\n"
  }

  private def writeSequenceContainsTest(ty: DataType): String = {
    val solidityTemplate =
      """
        |function sequenceContains({{type}}[] storage sequence, {{type}} element) private view returns (bool) {
        |    for (uint i = 0; i < sequence.length; i++) {
        |        if (sequence[i] == element) {
        |            return true;
        |        }
        |    }
        |    return false;
        |}
      """.trim.stripMargin

    val indentedTemplate = solidityTemplate.split("\n").map(INDENTATION_STR * indentationLevel + _).mkString("\n")
    indentedTemplate.replace("{{type}}", writeType(ty, payable = false))
  }

  private def writeParamHash(parameters: Seq[Variable]): String =
    s"keccak256(abi.encodePacked(${parameters.map(_.name).mkString(", ")}))"

  private def writeAllApprovesTest(withParams: Boolean): String = {
    val solidityTemplate = if (withParams) {
      """
        |function allApproved(address[] storage approvers,
        |                     mapping(bytes32 => mapping(address => bool)) storage approvals, bytes32 paramHash)
        |                     private view returns (bool) {
        |    for (uint i = 0; i < approvers.length; i++) {
        |        if (!approvals[paramHash][approvers[i]]) {
        |            return false;
        |        }
        |    }
        |    return true;
        |}
      """.trim.stripMargin
    } else {
      """
        |function allApproved(address[] storage approvers, mapping(address => bool) storage approvals))
        |         private view returns (bool) {
        |    for (uint i = 0; i < approvers.length; i++) {
        |        if (!approvals[approvers[i]]) {
        |            return false;
        |        }
        |    }
        |    return true;
        |}
      """.stripMargin
    }

    solidityTemplate.split("\n").map(INDENTATION_STR * indentationLevel + _).mkString("\n")
  }

  def writeSpecification(specification: Specification): String = specification match {
    case Specification(name, stateMachine, _) =>
      val builder = new StringBuilder()
      appendLine(builder, s"pragma solidity >=$SOLIDITY_VERSION;\n")
      appendLine(builder, s"contract $name {")

      val autoTransitions = stateMachine.transitions.filter(_.auto).foldLeft(Map.empty[String, Seq[Transition]]) { (autoTrans, transition) =>
        val originState = transition.origin.get
        autoTrans + (originState -> (autoTrans.getOrElse(originState, Seq.empty[Transition]) :+ transition))
      }

      val payableFields = extractPayableVars(stateMachine.flattenStatements, stateMachine.fields.map(_.name).toSet)

      indentationLevel += 1
      appendLine(builder, "enum State {")
      indentationLevel += 1
      stateMachine.states.toSeq.zipWithIndex.foreach { case (stateName, i) =>
        appendLine(builder, if (i < stateMachine.states.size - 1) stateName + "," else stateName)
      }
      indentationLevel -= 1
      appendLine(builder, "}")

      stateMachine.fields.foreach(f => appendLine(builder, writeField(f, payableFields.contains(f.name)) + ";"))
      appendLine(builder, s"State public $CURRENT_STATE_VAR;")
      builder.append(writeAuthorizationFields(stateMachine))
      builder.append("\n")

      stateMachine.transitions foreach { t => builder.append(writeTransition(t, autoTransitions)) }
      extractAllMembershipTypes(stateMachine).foreach(ty => builder.append(writeSequenceContainsTest(ty) + "\n"))
      builder.append("\n")

      val (transWithParams, transWithoutParams) = stateMachine.transitions.partition(_.parameters.isDefined)
      val allAuthCheck = transWithoutParams.flatMap(_.authorized).flatMap(_.flatten).exists {
        case AuthAll(_) => true
        case _ => false
      }
      if (allAuthCheck) {
        builder.append(writeAllApprovesTest(false) + "\n")
      }
      val allAuthParams = transWithParams.flatMap(_.authorized).flatMap(_.flatten).exists {
        case AuthAll(_) => true
        case _ => false
      }
      if (allAuthParams) {
        builder.append(writeAllApprovesTest(true) + "\n")
      }

      indentationLevel -= 1
      appendLine(builder, "}")
      builder.toString
  }

  private def extractMembershipTypes(expression: Expression): Set[DataType] = expression match {
    case LogicalOperation(left, In | NotIn, _) => Set(left.determinedType)
    case LogicalOperation(left, _, right) => extractMembershipTypes(left) ++ extractMembershipTypes(right)
    case ArithmeticOperation(left, _, right) => extractMembershipTypes(left) ++ extractMembershipTypes(right)
    case MappingRef(map, key) => extractMembershipTypes(map) ++ extractMembershipTypes(key)
    case SequenceSize(sequence) => extractMembershipTypes(sequence)
    case _ => Set.empty[DataType]
  }

  // Checks for any occurrences of the "in" or "not in" operators and the
  // element type of each sequence involved. This is used to auto-generate
  // type-specific helper functions to perform the membership check.
  private def extractAllMembershipTypes(stateMachine: StateMachine): Set[DataType] = {
    val expressionChecks = stateMachine.flattenExpressions.foldLeft(Set.empty[DataType]) { (current, exp) =>
      current.union(extractMembershipTypes(exp))
    }

    val authTerms = stateMachine.transitions.flatMap(_.authorized).flatMap(_.flatten)
    val authMembershipTest = authTerms.exists {
      case AuthAll(_) | AuthAny(_) =>
        true
      case _ => false
    }

    if (authMembershipTest) {
      expressionChecks + Identity
    } else {
      expressionChecks
    }
  }

  private def extractVarNames(expression: Expression): Set[String] = expression match {
    case MappingRef(map, key) => extractVarNames(map) ++ extractVarNames(key)
    case VarRef(name) => Set(name)
    case LogicalOperation(left, _, right) => extractVarNames(left) ++ extractVarNames(right)
    case ArithmeticOperation(left, _, right) => extractVarNames(left) ++ extractVarNames(right)
    case SequenceSize(sequence) => extractVarNames(sequence)
    case _ => Set.empty[String]
  }

  private def extractPayableVars(statements: Seq[Statement], scope: Set[String] = Set.empty[String]): Set[String] = {
    val names = statements.foldLeft(Set.empty[String]) { (current, statement) =>
      statement match {
        case Send(destination, _, _) => current.union(extractVarNames(destination))
        case _ => current
      }
    }

    if (scope.nonEmpty) {
      names.intersect(scope)
    } else {
      names
    }
  }
}
