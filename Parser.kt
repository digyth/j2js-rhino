import com.github.javaparser.ast.ArrayCreationLevel
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.utils.StringEscapeUtils
import java.lang.Exception

class Parser {
    val decompiler: Decompiler
    val loadedVar= arrayListOf<String>()

    constructor(decompiler: Decompiler) {
        this.decompiler = decompiler
    }

    fun parse(exp: Expression): Expression {
        return if (exp.isMethodCallExpr) {
            parseMethodCall(exp.asMethodCallExpr())
        } else if (exp.isFieldAccessExpr) {
            parseFieldAccess(exp.asFieldAccessExpr())
        } else if(exp.isBinaryExpr) {
            exp.asBinaryExpr().left=parse(exp.asBinaryExpr().left)
            exp.asBinaryExpr().right=parse(exp.asBinaryExpr().right)
            exp
        }else if(exp.isEnclosedExpr) {
            if(exp.asEnclosedExpr().inner.isBinaryExpr.not())
                parse(exp.asEnclosedExpr().inner)
            else
                exp
        }else if(exp.isNameExpr){
            checkThis(exp)
        }else{
            exp
        }
    }

    fun parseMethodCall(exp: MethodCallExpr): Expression {
        try {
            if (!exp.scope.isPresent.not() && exp.scope.get().toString()
                    .equals(decompiler.method.getParameter(0).nameAsString)
            ) {
                if (Main.clazz.getMethodsByName(exp.nameAsString).isEmpty()) return exp
                val dest = Main.clazz.getMethodsByName(exp.nameAsString).get(0)
                object : VoidVisitorAdapter<Any>() {
                    override fun visit(n: MethodCallExpr, arg: Any?) {
                        super.visit(n, arg)
                        n.replace(parse(n))
                    }
                }.visit(dest, null)
                return MethodCallExpr(null, Utils.parseThisToFunction(dest).toString())
            }
            return when ((if (exp.scope.isPresent.not()) "" else exp.scope.get()
                .toString()) + "." + exp.nameAsString) {
                "OptRuntime.bindThis", "OptRuntime.initFunction" -> exp.getArgument(
                    0
                )
                "ScriptRuntime.toBoolean", "OptRuntime.wrapDouble", "ScriptRuntime.toNumber", "ScriptRuntime.toInt32" -> {
                    val arg = parseArg(exp, 0)
                    if (arg.isBinaryExpr) EnclosedExpr(arg) else arg
                }
                "ScriptRuntime.wrapRegExp" -> parseArg(exp, 2)
                "ScriptRuntime.add" -> EnclosedExpr(
                    BinaryExpr(
                        exp.getArgument(0),
                        exp.getArgument(1),
                        BinaryExpr.Operator.PLUS
                    )
                )
                "ScriptRuntime.name" -> NameExpr(parseStr(exp, 2))
                "ScriptRuntime.getObjectProp", "ScriptRuntime.getObjectPropNoWarn" -> FieldAccessExpr(
                    parseArg(exp, 0),
                    parseStr(exp, 1)
                )
                "ScriptRuntime.getPropFunctionAndThis" -> MethodCallExpr(
                    parseArg(exp, 0),
                    parseStr(exp, 1)
                )
                "OptRuntime.call0" -> checkEval(parseArg(exp, 0).asMethodCallExpr())
                "OptRuntime.call1" -> {
                    val arg = parseArg(exp, 0)
                    val result = checkEval(arg.asMethodCallExpr())
                    result.arguments.add(parseArg(exp, 2))
                    result
                }
                "OptRuntime.call2" -> {
                    val arg = parseArg(exp, 0)
                    val result = checkEval(arg.asMethodCallExpr())
                    result.arguments.add(parseArg(exp, 2))
                    result.arguments.add(parseArg(exp, 3))
                    result
                }
                "OptRuntime.callN" -> {
                    checkEval(
                        exp.getArgument(0).asMethodCallExpr()
                    ).setArguments(Utils.getObjArrValue(this, exp.getArgument(2)))
                }
                "ScriptRuntime.setName" -> {
                    val arg = parseStr(exp, 4)
                    if (decompiler.function_vars.contains(arg) && loadedVar.contains(arg).not()) {
                        loadedVar.add(arg)
                        VariableDeclarationExpr(
                            VariableDeclarator(
                                ClassOrInterfaceType(
                                    null,
                                    "var"
                                ), arg, parseArg(exp, 1)
                            )
                        )
                    } else {
                        AssignExpr(NameExpr(arg), parseArg(exp, 1), AssignExpr.Operator.ASSIGN)
                    }
                }
                "ScriptRuntime.setConst" -> VariableDeclarationExpr(
                    VariableDeclarator(
                        ClassOrInterfaceType(null, "const"),
                        parseStr(exp, 3),
                        parseArg(exp, 1)
                    )
                )
                "ScriptRuntime.getPropFunctionAndThis", "OptRuntime.callProp0" -> MethodCallExpr(
                    checkThis(parseArg(exp, 0)),
                    parseStr(exp, 1)
                )
                "OptRuntime.newArrayLiteral" -> Utils.eval(
                    Utils.getObjArrValue(
                        this,
                        exp.getArgument(0)
                    ).toString()
                )
                "ScriptRuntime.newObject" -> {
                    val obj = parseArg(exp, 0)
                    ObjectCreationExpr(
                        null,
                        ClassOrInterfaceType(
                            if (obj.isFieldAccessExpr) Utils.exp2type(obj.asFieldAccessExpr().scope) else null,
                            if (obj.isFieldAccessExpr) obj.asFieldAccessExpr().nameAsString else obj.toString()
                        ),
                        Utils.getObjArrValue(this, exp.getArgument(3))
                    )
                }
                "OptRuntime.callName" -> MethodCallExpr(
                    null,
                    parseStr(exp, 1),
                    Utils.getObjArrValue(this, exp.getArgument(0))
                )
                "ScriptRuntime.eq", "ScriptRuntime.shallowEq" -> EnclosedExpr(
                    BinaryExpr(
                        parseArg(
                            exp,
                            0
                        ), parseArg(exp, 1), BinaryExpr.Operator.EQUALS
                    )
                )
                "ScriptRuntime.checkRegExpProxy(context).compileRegExp", "checkRegExpProxy.compileRegExp" -> ObjectCreationExpr(
                    null,
                    ClassOrInterfaceType(null, "RegExp"),
                    NodeList(exp.getArgument(1), exp.getArgument(2))
                )
                "OptRuntime.callSpecial" -> {
                    val arg = checkVar(parseArg(exp, 1)).asMethodCallExpr()
                    arg.setArguments(Utils.getObjArrValue(this, exp.getArgument(3)))
                }
                "ScriptRuntime.getNameFunctionAndThis", "ScriptRuntime.getValueFunctionAndThis", "OptRuntime.callName0" -> MethodCallExpr(
                    null,
                    parseStr(exp, 0)
                )
                "ScriptRuntime.setObjectProp" -> AssignExpr(
                    FieldAccessExpr(
                        parseArg(exp, 0),
                        parseStr(exp, 1)
                    ), parseArg(exp, 2), AssignExpr.Operator.ASSIGN
                )
                "ScriptRuntime.newObjectLiteral" -> {
                    val key = Utils.getObjArrValue(this, exp.getArgument(0))
                    val value = Utils.getObjArrValue(this, exp.getArgument(1))
                    val strArr = arrayListOf<String>()
                    if (key.size == value.size) {
                        for (i in key.indices) {
                            strArr.add(key[i].toString() + ":" + value[i])
                        }
                    } else {
                        println(exp)
                    }
                    Utils.eval("{" + strArr.joinToString() + "}")
                }
                "ScriptRuntime.cmp_LE" -> BinaryExpr(
                    parseArg(exp, 0),
                    parseArg(exp, 1),
                    BinaryExpr.Operator.LESS_EQUALS
                )
                "ScriptRuntime.cmp_LT" -> BinaryExpr(
                    parseArg(exp, 0),
                    parseArg(exp, 1),
                    BinaryExpr.Operator.LESS
                )
                "ScriptRuntime.getObjectElem" -> ArrayAccessExpr(parseArg(exp, 0), parseArg(exp, 1))
                "ScriptRuntime.setObjectElem" -> AssignExpr(
                    ArrayAccessExpr(
                        parseArg(exp, 0),
                        parseArg(exp, 1)
                    ), parseArg(exp, 2), AssignExpr.Operator.ASSIGN
                )
                "ScriptRuntime.getElemFunctionAndThis" -> MethodCallExpr(
                    null,
                    ArrayAccessExpr(parseArg(exp, 0), parseArg(exp, 1)).toString()
                )
                "ScriptRuntime.nameIncrDecr" -> UnaryExpr(
                    NameExpr(parseStr(exp, 1)),
                    when (exp.getArgument(3).asIntegerLiteralExpr().asNumber().toInt()) {
                        0 -> UnaryExpr.Operator.PREFIX_INCREMENT
                        1 -> UnaryExpr.Operator.PREFIX_DECREMENT
                        3 -> UnaryExpr.Operator.POSTFIX_DECREMENT
                        else -> UnaryExpr.Operator.POSTFIX_INCREMENT
                    }
                )
                "ScriptRuntime.escapeTextValue" -> parseArg(exp, 0)
                "ScriptRuntime.delete" -> {
                    val arg = parseArg(exp, 0)
                    if (arg.isNameExpr || arg.isFieldAccessExpr) {
                        Utils.eval("delete " + FieldAccessExpr(arg, parseStr(exp, 1)))
                    } else {
                        Utils.eval("delete " + parseStr(exp, 1))
                    }
                }
                "ScriptRuntime.typeofName"->MethodCallExpr("typeof",NameExpr(parseStr(exp,1)))
                else -> exp
            }
        }catch (err:Exception){
            err.printStackTrace()
            println(exp)
            return exp
        }
    }

    fun parseFieldAccess(exp: FieldAccessExpr): Expression {
        return when (exp.toString()) {
            "ScriptRuntime.emptyArgs"->ArrayCreationExpr(ClassOrInterfaceType(null,"Object[]"),NodeList<ArrayCreationLevel>(), ArrayInitializerExpr(NodeList()))
            "Undefined.instance"->NameExpr("undefined")
            "OptRuntime.zeroObj"->IntegerLiteralExpr("0")
            "OptRuntime.oneObj"->IntegerLiteralExpr("1")
            "OptRuntime.minusOneObj"->IntegerLiteralExpr("-1")
            "Boolean.TRUE"->BooleanLiteralExpr(true)
            "Boolean.FALSE"->BooleanLiteralExpr(false)
            else -> exp
        }
    }

    fun checkVar(exp: Expression): Expression {
        if (exp.isNameExpr) {
            if (decompiler.vars.containsKey(exp.asNameExpr().nameAsString)){
                val _var=decompiler.vars.get(exp.asNameExpr().nameAsString)!!.getVariable(0)
                if(!_var.initializer.isPresent.not())return _var.initializer.get()
            }
            return exp
        } else {
            return exp
        }
    }

    fun parseArg(exp:MethodCallExpr,index:Int):Expression{
        return parse(exp.getArgument(index))
    }

    fun parseStr(exp:MethodCallExpr,index:Int):String{
        val result=parse(exp.getArgument(index))
        return if(result.isStringLiteralExpr)result.asStringLiteralExpr().asString() else result.toString()
    }

    fun checkEval(exp:MethodCallExpr):MethodCallExpr{
        if(exp.asMethodCallExpr().nameAsString.equals("eval")){
            return MethodCallExpr(null,exp.toString())
        }else{
            return exp
        }
    }

    fun checkThis(exp:Expression):Expression{
        return if(exp.isNameExpr&&exp.asNameExpr().nameAsString.equals(decompiler.method.getParameter(3).nameAsString)){
            NameExpr("this")
        }else{
            exp
        }
    }

}