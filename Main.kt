import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import java.io.File

class Main {
    enum class functionInfo {
        NAME, PARAMS, VARS, METHOD_NAME, METHOD
    }

    companion object {

        val functionInfos = HashMap<Int, HashMap<functionInfo, Any>>()
        var className = ""
        var clazz: ClassOrInterfaceDeclaration = ClassOrInterfaceDeclaration()

        /***
         * @param file a java source code file
         */
        fun run(file: File): String {
            return run(file, file.name.substring(0, file.name.indexOf('.')))
        }


        /***
         * @param file a java source code file
         * @param className the name of main class
         */
        fun run(file: File, className: String): String {
            if (!file.exists()) return ""
            return run(file.readText(),className)
        }

        /***
         * @param str java source code
         * @param className the name of main class
         */
        fun run(str: String, className: String): String {
            val unit = StaticJavaParser.parse(str)
            this.className = className
            val optClass = unit.getClassByName(className)
            println("---decompile run---")
            println("className:"+className)
            if (optClass.isPresent) {
                clazz = optClass.get()
                val garbageCleaner = GarbageCleaner()
                //修改catch变量名
                object : VoidVisitorAdapter<Any>() {
                    override fun visit(n: CatchClause, arg: Any?) {
                        super.visit(n, arg)
                        object : VoidVisitorAdapter<Any>() {
                            override fun visit(call: MethodCallExpr, arg: Any?) {
                                super.visit(call, arg)
                                if (call.nameAsString.equals("newCatchScope")) {
                                    n.parameter.setName(
                                        call.getArgument(2).asStringLiteralExpr().asString()
                                    )
                                }
                            }
                        }.visit(n.body, null)
                    }
                }.visit(unit, null)
                //清理无意义节点
                val remove = arrayListOf<TryStmt>()
                object : VoidVisitorAdapter<Any>() {
                    override fun visit(n: TryStmt, arg: Any?) {
                        super.visit(n, arg)
                        if (n.finallyBlock.isPresent && n.finallyBlock.get().statements.isNonEmpty) {
                            val list = NodeList<Statement>()
                            for (statement in n.finallyBlock.get().statements) {
                                if (statement.isExpressionStmt) {
                                    if (!findMethod(
                                            statement.asExpressionStmt(),
                                            arrayOf(
                                                "leaveWith",
                                                "enterWith",
                                                "exitActivationFunction",
                                                "enterActivationFunction"
                                            )
                                        )
                                    ) {
                                        list.add(statement)
                                    }
                                } else {
                                    list.add(statement)
                                }
                            }
                            if (list.size > 0) {
                                n.finallyBlock.get().setStatements(list)
                            } else {
                                if (n.catchClauses.size > 0) {
                                    n.removeFinallyBlock()
                                } else {
                                    remove.add(n)
                                }
                            }
                        }
                        if (n.catchClauses.isNonEmpty) {
                            if (n.catchClauses.size > 1) n.catchClauses.removeLast()
                            val list = NodeList<Statement>()
                            for (catchClause in n.catchClauses) {
                                for (statement in catchClause.body.statements) {
                                    if (statement.isExpressionStmt) {
                                        if (!statement.asExpressionStmt().expression.toString()
                                                .trim()
                                                .isEmpty() && !findMethod(
                                                statement.asExpressionStmt(),
                                                arrayOf("leaveWith", "exitActivationFunction")
                                            )
                                        ) {
                                            list.add(statement)
                                        }
                                    } else {
                                        list.add(statement)
                                    }
                                }
                            }
                            if (list.size > 0) {
                                val first = n.catchClauses.get(0)
                                first.body.setStatements(list)
                                n.setCatchClauses(NodeList(first))
                            } else {
                                remove.add(n)
                            }
                        }
                    }
                }.visit(unit, null)
                for (n in remove) {
                    val statements = Utils.getParentBlockStmt(n).statements
                    for (index in statements.indices) {
                        if (statements[index].equals(n)) {
                            statements.addAll(index, n.tryBlock.statements)
                            n.removeFinallyBlock()
                            n.remove()
                            break
                        }
                    }
                }
                object : VoidVisitorAdapter<Any>() {
                    val varNames = arrayOf("parentScope", "createFunctionActivation")
                    val methodNames = arrayOf(
                        "enterActivationFunction",
                        "exitActivationFunction",
                        "leaveWith",
                        "enterWith",
                        "getParentScope",
                        "initScript",
                        "_reInit"
                    )
                    val assignMatchName = arrayOf("enterWith\\d*$", "leaveWith\\d*$")

                    override fun visit(n: ReturnStmt, arg: Any?) {
                        super.visit(n, arg)
                        if (!n.expression.isPresent) return
                        val str = n.expression.get().toString()
                        if (str.equals("obj") || str.equals("Undefined.instance")) garbageCleaner.cleanMe(
                            n
                        )
                    }

                    override fun visit(n: VariableDeclarationExpr, arg: Any?) {
                        super.visit(n, arg)
                        val thisVar = n.getVariable(0)
                        if (varNames.contains(thisVar.nameAsString)) garbageCleaner.cleanParent(n)
                        else if (thisVar.initializer.isPresent && thisVar.initializer.get()
                                .toString()
                                .equals("Undefined.instance")
                        ) garbageCleaner.cleanParent(n)
                        else
                            for (s in assignMatchName) {
                                if (thisVar.nameAsString.matches(Regex(s))) garbageCleaner.cleanParent(
                                    n
                                )
                            }
                    }

                    override fun visit(n: MethodCallExpr, arg: Any?) {
                        super.visit(n, arg)
                        if (methodNames.contains(n.nameAsString)&&(n.parentNode.get().javaClass.equals(VariableDeclarator::class.java)||n.parentNode.get().javaClass.equals(ExpressionStmt::class.java))) garbageCleaner.cleanParent(n)
                    }

                    override fun visit(n: IfStmt, arg: Any?) {
                        super.visit(n, arg)
                        if (n.condition.isBinaryExpr && n.condition.asBinaryExpr().left.toString()
                                .equals("objArr.length")
                        ) garbageCleaner.cleanMe(n)
                        if (n.condition.isUnaryExpr && n.condition.asUnaryExpr().expression.isMethodCallExpr && n.condition.asUnaryExpr().expression.asMethodCallExpr().nameAsString.equals(
                                "hasTopCall"
                            )
                        ) garbageCleaner.cleanMe(n)
                    }

                    override fun visit(n: CatchClause, arg: Any?) {
                        super.visit(n, arg)
                        if (!n.parameter.typeAsString.equals("Exception")) n.parameter.setType("Exception")
                    }

                    override fun visit(n: ThrowStmt, arg: Any?) {
                        super.visit(n, arg)
                        if (n.expression.toString().equals("th")) garbageCleaner.cleanMe(n)
                    }

                    override fun visit(n: AssignExpr, arg: Any?) {
                        super.visit(n, arg)
                        if (n.target.equals(n.value)) garbageCleaner.cleanParent(n)
                        else if (n.value.isMethodCallExpr && methodNames.contains(n.value.asMethodCallExpr().nameAsString)) garbageCleaner.cleanParent(
                            n
                        )
                        else if (n.target.isNameExpr) {
                            val name = n.target.asNameExpr().nameAsString
                            for (s in assignMatchName) {
                                if (name.matches(Regex(s))) {
                                    garbageCleaner.cleanParent(n)
                                    return
                                }
                            }
                            if (n.value.isFieldAccessExpr && n.value.asFieldAccessExpr().toString()
                                    .equals("Undefined.instance")
                            ) garbageCleaner.cleanParent(n)
                            else if(n.value.isNameExpr&&n.target.asNameExpr().nameAsString.matches(Regex("e\\d*"))&&n.value.asNameExpr().nameAsString.matches(Regex("e\\d*"))){
                                garbageCleaner.cleanParent(n)
                            }
                        }
                    }

                    override fun visit(n: CastExpr, arg: Any?) {
                        super.visit(n, arg)
                        n.replace(n.expression)
                    }


                }.visit(unit, null)
                garbageCleaner.clean()
                //全局常数分发
                val map = HashMap<String, Expression>()
                if (clazz.getMethodsByName("_reInit").isNotEmpty()) {
                    object : VoidVisitorAdapter<Any>() {
                        override fun visit(n: AssignExpr, arg: Any?) {
                            super.visit(n, arg)
                            map.put(n.target.toString(), n.value)
                        }
                    }.visit(clazz.getMethodsByName("_reInit").get(0), null)
                }
                object : VoidVisitorAdapter<Any>() {

                    override fun visit(n: FieldDeclaration, arg: Any?) {
                        super.visit(n, arg)
                        if (n.getVariable(0).initializer.isPresent) map.put(
                            n.getVariable(0).nameAsString,
                            n.getVariable(0).initializer.get()
                        )
                    }

                    override fun visit(n: NameExpr, arg: Any?) {
                        super.visit(n, arg)
                        if (map.containsKey(n.nameAsString)) n.replace(map.get(n.nameAsString))
                    }
                }.visit(unit, null)
                //收集信息
                var methodCall: HashMap<Int, NodeList<Expression>>
                try {
                    methodCall = Utils.parseSwitch(
                        clazz.getMethodsByName("call").get(0).body.get().getStatement(0)
                            .asSwitchStmt()
                    )
                } catch (err: Exception) {
                    methodCall = hashMapOf()
                    object : VoidVisitorAdapter<Any>() {
                        override fun visit(n: MethodCallExpr, arg: Any?) {
                            super.visit(n, arg)
                            if (!n.scope.isPresent) methodCall.put(0, NodeList(n))
                        }
                    }.visit(clazz.getMethodsByName("call").get(0), null)
                }
                var methodGetFunctionName: HashMap<Int, NodeList<Expression>>
                try {
                    methodGetFunctionName = Utils.parseSwitch(
                        clazz.getMethodsByName("getFunctionName").get(0).body.get().getStatement(0)
                            .asSwitchStmt()
                    )
                } catch (err: Exception) {
                    methodGetFunctionName =
                        hashMapOf(Pair(0, NodeList<Expression>(StringLiteralExpr(""))))
                }
                var methodGetParamCount: HashMap<Int, NodeList<Expression>>
                try {
                    methodGetParamCount = Utils.parseSwitch(
                        clazz.getMethodsByName("getParamCount").get(0).body.get().getStatement(0)
                            .asSwitchStmt()
                    )
                } catch (err: Exception) {
                    methodGetParamCount =
                        hashMapOf(Pair(0, NodeList<Expression>(IntegerLiteralExpr("0"))))
                }
                var methodGetParamOrVarName: HashMap<Int, NodeList<Expression>>
                try {
                    methodGetParamOrVarName = Utils.parseSwitch(
                        clazz.getMethodsByName("getParamOrVarName").get(0).body.get()
                            .getStatement(0)
                            .asSwitchStmt()
                    )
                } catch (err: Exception) {
                    methodGetParamOrVarName =
                        hashMapOf(Pair(0, NodeList<Expression>(StringLiteralExpr(""))))
                }
                for (i in 0 until methodCall.size) {
                    var nodeList: NodeList<Expression>?
                    val infos = HashMap<functionInfo, Any>()
                    val methodName = methodCall.get(i)!!.get(0).asMethodCallExpr().nameAsString
                    infos.put(functionInfo.METHOD_NAME, methodName)
                    infos.put(functionInfo.METHOD, clazz.getMethodsByName(methodName).get(0))
                    infos.put(
                        functionInfo.NAME,
                        methodGetFunctionName.get(i)!!.get(0).asStringLiteralExpr().asString()
                    )
                    val paramCount =
                        if (methodGetParamCount.get(i)
                                .isNullOrEmpty()
                        ) 0 else methodGetParamCount.get(i)!!.get(0)
                            .asIntegerLiteralExpr().asNumber().toInt()
                    val paramOrVarName = arrayListOf<String>()
                    nodeList = methodGetParamOrVarName.get(i)
                    if (!nodeList.isNullOrEmpty()) {
                        for (expression in nodeList) {
                            if (expression.isStringLiteralExpr) paramOrVarName.add(
                                expression.asStringLiteralExpr().asString()
                            )
                        }
                    }
                    infos.put(functionInfo.PARAMS, paramOrVarName.subList(0, paramCount))
                    infos.put(
                        functionInfo.VARS,
                        paramOrVarName.subList(paramCount, paramOrVarName.size)
                    )
                    functionInfos.put(i, infos)
                }
                //获取加载顺序
                val methodSort = arrayListOf<Int>()
                val mainMethod =
                    functionInfos.get(0)!!.get(functionInfo.METHOD) as MethodDeclaration
                findFunctionCall(mainMethod, methodSort)
                methodSort.add(0)
                for (int in methodSort) {
                    val decompiler = Decompiler(int)
                    decompiler.parseParam()
                    decompiler.parseConst()
                    decompiler.parseFunction()
                    decompiler.preOptimizeStmt()
                    decompiler.optimizeVar()
                    decompiler.parseMethod()
                    decompiler.parseVar()
                    decompiler.optimizeStmt()
                    decompiler.optimizeVar()
                    decompiler.optimizeBinaryExpr()
                    decompiler.optimizeConditionExpr()
                    decompiler.optimizeUnaryExpr()
                    decompiler.optimizeVar()
                    decompiler.removeRepeat()
                    decompiler.optimizeVarDef()
                }
                val mainStatements = mainMethod.body.get().statements
                val lastStatement = mainStatements.last.get()
                if (lastStatement.isReturnStmt) {
                    if (!lastStatement.asReturnStmt().expression.isPresent) {
                        lastStatement.remove()
                    } else {
                        lastStatement.replace(ExpressionStmt(lastStatement.asReturnStmt().expression.get()))
                    }
                }
                for (mainStatement in mainStatements) {
                    if (mainStatement.isExpressionStmt && mainStatement.asExpressionStmt().expression.isStringLiteralExpr && mainStatement.asExpressionStmt().expression.asStringLiteralExpr()
                            .asString().equals("ui")
                    ) {
                        mainStatement.remove()
                        mainStatements.add(0, ExpressionStmt(StringLiteralExpr("ui")))
                        break
                    }
                }
                val result = Utils.getMethodBody(mainMethod)
                val fixer = Fixer(result)
                do {
                    fixer.expand("eval", "function ")
                    fixer.expand("eval", "function(){")
                    fixer.expand("eval", "{")
                    fixer.expand("eval", "[")
                    fixer.expand("eval", "for (")
                    fixer.expand("eval", "delete ")
                    fixer.expand("new XML", "<")
                } while (fixer.getAndResetCount() > 0)
                return fixer.getContent()
            }
            return unit.toString()
        }

        fun findFunctionCall(declaration: MethodDeclaration, list: ArrayList<Int>) {
            object : VoidVisitorAdapter<Any>() {
                override fun visit(n: ObjectCreationExpr, arg: Any?) {
                    super.visit(n, arg)
                    if (n.typeAsString.equals(className)) {
                        val index = n.getArgument(2).asIntegerLiteralExpr().asNumber().toInt()
                        findFunctionCall(
                            functionInfos.get(index)!!
                                .get(functionInfo.METHOD) as MethodDeclaration,
                            list
                        )
                        list.add(index)
                    }
                }
            }.visit(declaration, null)
        }

        private fun findMethod(node: ExpressionStmt, names: Array<String>): Boolean {
            var result = false
            object : VoidVisitorAdapter<Any>() {
                override fun visit(n: MethodCallExpr, arg: Any?) {
                    super.visit(n, arg)
                    if (names.contains(n.nameAsString)) result = true
                }
            }.visit(node, null)
            return result
        }
    }
}