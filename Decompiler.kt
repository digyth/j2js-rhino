import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import java.util.function.Consumer

class Decompiler {
    companion object {
        val log = arrayListOf<String>()
    }

    val method: MethodDeclaration
    val methodIndex: Int
    val garbageCleaner = GarbageCleaner()
    val vars = HashMap<String, VariableDeclarationExpr>()
    val function_vars: List<String>
    val function_params: List<String>
    val parser = Parser(this)

    constructor(index: Int) {
        methodIndex = index
        this.method =
            Main.functionInfos.get(index)!!.get(Main.functionInfo.METHOD) as MethodDeclaration
        this.function_vars =
            Main.functionInfos.get(methodIndex)!!.get(Main.functionInfo.VARS) as List<String>
        this.function_params =
            Main.functionInfos.get(methodIndex)!!.get(Main.functionInfo.PARAMS) as List<String>
        resetVars()
    }

    private fun resetVars() {
        vars.clear()
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: VariableDeclarationExpr, arg: Any?) {
                super.visit(n, arg)
                n.variables.forEach(Consumer { _var ->
                    vars.put(_var.nameAsString, n)
                })
            }
        }.visit(method, null)
    }

    fun optimizeConditionExpr() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(block: BlockStmt, arg: Any?) {
                super.visit(block, arg)
                var offset=0
                for (i in block.statements.indices) {
                    if(block.statements[i-offset].isIfStmt){
                        val n=block.statements[i-offset].asIfStmt()
                        if(n.hasThenBlock()&&n.hasElseBlock()&&n.thenStmt.asBlockStmt().statements.size==1&&n.elseStmt.get().asBlockStmt().statements.size==1){
                            val thenStmt=n.thenStmt.asBlockStmt().getStatement(0)
                            val elseStmt=n.elseStmt.get().asBlockStmt().getStatement(0)
                            if(thenStmt.isExpressionStmt&&elseStmt.isExpressionStmt&&thenStmt.asExpressionStmt().expression.isAssignExpr&&elseStmt.asExpressionStmt().expression.isAssignExpr){
                                val thenExp=thenStmt.asExpressionStmt().expression.asAssignExpr()
                                val elseExp=elseStmt.asExpressionStmt().expression.asAssignExpr()
                                if(thenExp.target.isNameExpr&&elseExp.target.isNameExpr){
                                    val name=thenExp.target.asNameExpr().nameAsString
                                    if(name == elseExp.target.asNameExpr().nameAsString&&vars.containsKey(name)) {
                                        block.statements.removeAt(i)
                                        offset++
                                        Utils.replaceNameExpr(
                                            thenExp.target.asNameExpr().nameAsString,
                                            ConditionalExpr(n.condition, thenExp.value, elseExp.value),
                                            method
                                        )
                                        garbageCleaner.cleanParent(vars.get(name)!!)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
        }.visit(method,null)
        object :VoidVisitorAdapter<Any>(){
            override fun visit(n: ConditionalExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.thenExpr.isBooleanLiteralExpr&&n.elseExpr.isBooleanLiteralExpr){
                    if(n.condition.isUnaryExpr){
                        if(n.thenExpr.asBooleanLiteralExpr().value){
                            n.replace(MethodCallExpr("Boolean",n.condition))
                        }else{
                            n.replace(MethodCallExpr("Boolean",n.condition.asUnaryExpr().expression))
                        }
                    }else{
                        if(n.thenExpr.asBooleanLiteralExpr().value){
                            n.replace(MethodCallExpr("Boolean",n.condition))
                        }else{
                            n.replace(MethodCallExpr("Boolean",UnaryExpr(n.condition,UnaryExpr.Operator.LOGICAL_COMPLEMENT)))
                        }
                    }
                }
            }
        }.visit(method,null)
        garbageCleaner.clean()
        if (log.contains(method.nameAsString)) System.out.println("optimizeConditionExpr:\n"+ method.toString())
    }

    fun optimizeVar() {
        resetVars()

        for (entry in vars) {
            val _var = entry.value.getVariable(0)
            if (_var.initializer.isPresent) {
                var init = _var.initializer.get()
                if (init.isArrayCreationExpr && init.asArrayCreationExpr().levels.size > 0 && init.asArrayCreationExpr().levels.get(
                        0
                    ).dimension.get().isIntegerLiteralExpr
                ) {
                    val list = arrayOfNulls<Expression>(
                        init.asArrayCreationExpr().levels.get(0).dimension.get()
                            .asIntegerLiteralExpr().asNumber().toInt()
                    )
                    object : VoidVisitorAdapter<Any>() {
                        override fun visit(n: AssignExpr, arg: Any?) {
                            super.visit(n, arg)
                            if (n.target.isArrayAccessExpr && n.target.asArrayAccessExpr().name.toString()
                                    .equals(_var.nameAsString)
                            ) {
                                garbageCleaner.cleanParent(n)
                                list[n.target.asArrayAccessExpr().index.asIntegerLiteralExpr()
                                    .asNumber().toInt()] = n.value
                            }
                        }
                    }.visit(method, null)
                    val nodeList = NodeList<Expression>()
                    for (expression in list) {
                        nodeList.add(expression)
                    }
                    _var.setInitializer(
                        ArrayCreationExpr(
                            ClassOrInterfaceType(null, "Object[]"),
                            NodeList(),
                            ArrayInitializerExpr(nodeList)
                        )
                    )
                    garbageCleaner.clean()
                }
            }
        }

        resetVars()

        for (entry in vars) {
            val _var = entry.value.getVariable(0)
            if (Utils.isJsType(_var.typeAsString)) continue
            if (_var.initializer.isPresent) {
                var init = _var.initializer.get()
                if (_var.nameAsString.matches(Regex("objArr\\d")) || (init.isNameExpr && (function_params.contains(
                        init.asNameExpr().nameAsString
                    ) || function_vars.contains(init.asNameExpr().nameAsString) || vars.containsKey(
                        init.asNameExpr().nameAsString
                    )))
                ) {
                    Utils.replaceNameExpr(_var.nameAsString,init,method)
                    garbageCleaner.cleanParent(entry.value)
                    garbageCleaner.clean()
                    continue
                }
                if (init.isMethodCallExpr.not() || init.asMethodCallExpr().nameAsString.equals("eval")
                        .not()
                ) {
                    val list = arrayListOf<NameExpr>()
                    object : VoidVisitorAdapter<Any>() {
                        override fun visit(n: NameExpr, arg: Any?) {
                            super.visit(n, arg)
                            if (n.nameAsString.equals(_var.nameAsString)) {
                                list.add(n)
                            }
                        }
                    }.visit(method, null)
                    if (list.size <= 1) {
                        if (list.size == 1) list[0].replace(init)
                        garbageCleaner.cleanParent(entry.value)
                        garbageCleaner.clean()
                    }
                }
            }else if(_var.nameAsString.matches(Regex("objArr\\d"))){
                var value:Expression=ArrayCreationExpr(ClassOrInterfaceType(null,"Object[]"),
                    NodeList(), ArrayInitializerExpr
                (NodeList())
                )
                object :VoidVisitorAdapter<Any>(){
                    override fun visit(n: AssignExpr, arg: Any?) {
                        super.visit(n, arg)
                        if(n.target.isNameExpr&&n.target.asNameExpr().nameAsString.equals(_var.nameAsString)){
                            garbageCleaner.cleanParent(n)
                            value=n.value
                        }
                    }
                }.visit(method,null)
                Utils.replaceNameExpr(_var.nameAsString,value,method)
            }
        }
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: ExpressionStmt, arg: Any?) {
                super.visit(n, arg)
                if(n.expression.isEnclosedExpr&&n.expression.asEnclosedExpr().inner.isNameExpr)garbageCleaner.cleanMe(n)
            }

            override fun visit(n: EnclosedExpr, arg: Any?) {
                super.visit(n, arg)
                try{
                    if(n.inner.isNameExpr){
                        n.replace(n.inner)
                    }
                    else if(n.parentNode.isPresent){
                        if((n.parentNode.get() as Expression).isMethodCallExpr){
                            n.replace(n.inner)
                        }
                    }
                }catch (err:Exception){
                    n.replace(n.inner)
                }
            }
        }.visit(method, null)
        garbageCleaner.clean()
        if (log.contains(method.nameAsString)) System.out.println("optimizeVar:\n"+ method.toString())
    }

    fun parseConst() {
        object : VoidVisitorAdapter<Any>() {

            override fun visit(n: VariableDeclarationExpr, arg: Any?) {
                super.visit(n, arg)
                n.variables.forEach(Consumer { _var ->
                    if (!_var.initializer.isPresent.not() && _var.initializer.get().isFieldAccessExpr) _var.setInitializer(
                        parser.parse(_var.initializer.get())
                    )
                })
            }

            override fun visit(n: FieldAccessExpr, arg: Any?) {
                super.visit(n, arg)
                n.replace(parser.parse(n))
            }

            override fun visit(n: DoubleLiteralExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.value.endsWith("d")) n.replace(
                    DoubleLiteralExpr(
                        n.asDouble().toString()
                    )
                )
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("parseConst:\n"+ method.toString())
    }

    fun parseParam() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: ArrayAccessExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.name.isNameExpr && n.name.asNameExpr().nameAsString.equals("objArr") && n.index.isIntegerLiteralExpr) n.replace(
                    NameExpr(
                        (Main.functionInfos.get(methodIndex)!!
                            .get(Main.functionInfo.PARAMS) as List<String>).get(
                            n.index.asIntegerLiteralExpr().asNumber().toInt()
                        )
                    )
                )
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("parseParam:\n"+ method.toString())
    }

    fun preOptimizeStmt() {
        val indexs = hashMapOf<Int, SwitchStmt>()
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: IfStmt, arg: Any?) {
                super.visit(n, arg)
                val parentNode = n.parentNode.get()
                if (parentNode.javaClass.equals(IfStmt::class.java)) return
                val stmts = Utils.getAllIf(n)
                if (stmts.size > 1) {
                    val conditions = ArrayList<ArrayList<Expression>>()
                    for (stmt in stmts) {
                        val result = Utils.getConditions(stmt.condition, BinaryExpr.Operator.OR)
                        if (result.isEmpty()) {
                            return
                        }
                        conditions.add(result)
                    }
                    for (condition in conditions) {
                        for (expression in condition) {
                            if (!expression.isMethodCallExpr || !expression.asMethodCallExpr().nameAsString.equals(
                                    "shallowEq"
                                )
                            ) {
                                return
                            }
                        }
                    }
                    val exp = conditions[0][0].asMethodCallExpr().getArgument(1)
                    for (condition in conditions) {
                        for (expression in condition) {
                            if (!expression.asMethodCallExpr().getArgument(1).equals(exp)) return
                        }
                    }
                    val nodeList = NodeList<SwitchEntry>()
                    for (stmt in stmts.indices) {
                        for (expression in conditions[stmt].indices)
                            nodeList.add(
                                SwitchEntry(
                                    NodeList(
                                        conditions[stmt][expression].asMethodCallExpr()
                                            .getArgument(0)
                                    ),
                                    SwitchEntry.Type.STATEMENT_GROUP,
                                    if (expression == conditions[stmt].size - 1) stmts[stmt].thenStmt.asBlockStmt().statements else NodeList()
                                )
                            )
                    }
                    indexs.put(
                        Utils.getIndex(parentNode as BlockStmt, n),
                        SwitchStmt(exp, nodeList)
                    )
                }
            }
        }.visit(method, null)
        val statements = method.body.get().statements
        for (index in indexs) {
            statements.removeAt(index.key)
            statements.add(index.key, index.value)
        }
        if (log.contains(method.nameAsString)) if (log.contains(method.nameAsString)) System.out.println(
            "preOptimizeStmt:\n"+
            method.toString()
        )
    }

    fun optimizeStmt() {
        object:VoidVisitorAdapter<Any>(){
            override fun visit(n: NameExpr, arg: Any?) {
                super.visit(n, arg)
                val parent:Node=n.parentNode.get()
                if(parent.javaClass.equals(ExpressionStmt::class.java))garbageCleaner.cleanMe(parent);
            }
        }.visit(method,null)
        garbageCleaner.clean()
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: BlockStmt, arg: Any?) {
                super.visit(n, arg)
                val statements = n.statements
                for (index in statements.indices) {
                    if (index >= statements.size) return
                    if (index == 0) continue
                    if(statements[index].isIfStmt){
                        val condition=statements[index].asIfStmt().condition
                        if(condition.isUnaryExpr&&condition.asUnaryExpr().operator.equals(UnaryExpr.Operator.LOGICAL_COMPLEMENT)&&condition.asUnaryExpr().expression.isNameExpr){
                            val name=condition.asUnaryExpr().expression.asNameExpr().nameAsString
                            if(statements[index-1].isExpressionStmt&&statements[index-1].asExpressionStmt().expression.isVariableDeclarationExpr&&statements[index].asIfStmt().thenStmt.asBlockStmt().statements.size==1&&statements[index].asIfStmt().thenStmt.asBlockStmt().getStatement(0).isExpressionStmt&&statements[index].asIfStmt().thenStmt.asBlockStmt().getStatement(0).asExpressionStmt().expression.isAssignExpr){
                                val _var=statements[index-1].asExpressionStmt().expression.asVariableDeclarationExpr().getVariable(0)
                                val assign=statements[index].asIfStmt().thenStmt.asBlockStmt().getStatement(0).asExpressionStmt().expression.asAssignExpr()
                                if(_var.initializer.isPresent&&assign.target.isNameExpr&&_var.nameAsString.equals(name)&&assign.target.asNameExpr().nameAsString.equals(name)){
                                    val expr=BinaryExpr(_var.initializer.get(),assign.value,BinaryExpr.Operator.OR)
                                    garbageCleaner.cleanMe(statements[index])
                                    statements.removeAt(index)
                                    Utils.replaceNameExpr(name,expr,method)
                                }
                            }
                        }
                    }
                    if (statements[index].isWhileStmt && statements[index].asWhileStmt().condition.isMethodCallExpr && statements[index].asWhileStmt().condition.asMethodCallExpr().nameAsString.equals(
                            "enumNext"
                        )
                    ) {
                        val first =
                            statements[index].asWhileStmt().body.asBlockStmt().statements.first.get()
                                .asExpressionStmt().expression
                        statements[index].asWhileStmt().body.asBlockStmt().statements.removeFirst()
                        statements.add(
                            index + 1,
                            ExpressionStmt(
                                Utils.eval(
                                    ForEachStmt(
                                        VariableDeclarationExpr(
                                            ClassOrInterfaceType(null, "var"),
                                            if (first.isAssignExpr) first.asAssignExpr().target.toString() else first.asVariableDeclarationExpr()
                                                .getVariable(0).nameAsString
                                        ),
                                        statements[index - 1].asExpressionStmt().expression.asVariableDeclarationExpr()
                                            .getVariable(0).initializer.get().asMethodCallExpr()
                                            .getArgument(0),
                                        statements[index].asWhileStmt().body
                                    ).toString().replaceFirst(":", "in")
                                )
                            )
                        )
                        statements.removeAt(index)
                        statements.removeAt(index - 1)
                    } else if (statements[index].isWhileStmt && statements[index - 1].isExpressionStmt && !Utils.getVarName(
                            statements[index - 1].asExpressionStmt().expression
                        ).isEmpty()
                    ) {
                        val last =
                            statements[index].asWhileStmt().body.asBlockStmt().statements.last.get()
                        if (last.isExpressionStmt && Utils.getDefVarName(last.asExpressionStmt().expression)
                                .equals(Utils.getVarName(statements[index - 1].asExpressionStmt().expression))
                        ) {
                            val update = last.asExpressionStmt().expression
                            statements[index].asWhileStmt().body.asBlockStmt().statements.removeLast()
                            statements.add(
                                index + 1, ForStmt(
                                    NodeList(statements[index - 1].asExpressionStmt().expression),
                                    if (statements[index].asWhileStmt().condition.isBooleanLiteralExpr) {
                                        val temp =
                                            statements[index].asWhileStmt().body.asBlockStmt().statements
                                        val ret = temp.first.get()
                                            .asIfStmt().condition.asUnaryExpr().expression
                                        temp.removeFirst()
                                        ret
                                    } else statements[index].asWhileStmt().condition,
                                    NodeList(update),
                                    statements[index].asWhileStmt().body
                                )
                            )
                            statements.removeAt(index)
                            statements.removeAt(index - 1)
                        }
                    }
                }
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("optimizeStmt:\n"+ method.toString())
    }

    fun parseMethod() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: MethodCallExpr, arg: Any?) {
                super.visit(n, arg)
                n.replace(parser.parse(n))
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("parseMethod:\n"+ method.toString())
    }

    fun parseFunction() {
        Utils.parseToFunction(method)
        if (log.contains(method.nameAsString)) System.out.println("parseFunction:\n"+ method.toString())
    }

    fun parseVar() {
        resetVars()
        object : VoidVisitorAdapter<Any>() {
            var index = -1
            val _vars =
                Main.functionInfos.get(methodIndex)!!.get(Main.functionInfo.VARS) as List<String>

            override fun visit(n: VariableDeclarationExpr, arg: Any?) {
                super.visit(n, arg)
                for (_var in n.variables) {
                    if (Utils.isJsType(_var.typeAsString).not()) {
                        if (!_var.initializer.isPresent.not() && _var.initializer.get().isMethodCallExpr && _var.initializer.get()
                                .asMethodCallExpr().nameAsString.equals("enumInit")
                        ) return
                        if (++index < _vars.size&&vars.containsKey(_vars.get(index)).not()) {
                            object : VoidVisitorAdapter<Any>() {
                                override fun visit(n: NameExpr, arg: Any?) {
                                    super.visit(n, arg)
                                    if (n.nameAsString.equals(_var.nameAsString)) n.setName(
                                        _vars.get(
                                            index
                                        )
                                    )
                                }
                            }.visit(method, null)
                            _var.setType("var")
                            _var.setName(_vars.get(index))
                        }
                    }
                }
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("parseVar:\n"+ method.toString())
    }

    fun removeRepeat() {
        resetVars()
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: VariableDeclarationExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.variables.isEmpty()) {
                    garbageCleaner.cleanParent(n)
                    return
                }
                val _var = n.getVariable(0)
                if (vars.containsKey(_var.nameAsString) && _var.initializer.isPresent.not()) {
                    garbageCleaner.cleanParent(n)
                }
            }
        }.visit(method, null)
        garbageCleaner.clean()
        if (log.contains(method.nameAsString)) System.out.println("removeRepeat:\n"+ method.toString())
    }

    fun optimizeBinaryExpr() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: BinaryExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.operator.equals(BinaryExpr.Operator.OR)) {
                    if (n.left.isBinaryExpr && n.right.isBinaryExpr && n.left.asBinaryExpr().operator.equals(
                            BinaryExpr.Operator.EQUALS
                        ) && n.right.asBinaryExpr().operator.equals(BinaryExpr.Operator.EQUALS) && n.left.asBinaryExpr().right.isNullLiteralExpr && n.right.asBinaryExpr().right.toString()
                            .equals("undefined")
                    ) {
                        if (n.left.asBinaryExpr().left.isNameExpr && n.right.asBinaryExpr().left.isNameExpr && n.left.asBinaryExpr().left.asNameExpr().nameAsString.equals(
                                n.right.asBinaryExpr().left.asNameExpr().nameAsString
                            )
                        ) {
                            val nameExp=n.left.asBinaryExpr().left.asNameExpr()
                            if(vars.containsKey(nameExp.nameAsString)) {
                                n.replace(UnaryExpr(vars.get(nameExp.nameAsString)!!.getVariable(0).initializer.get(),UnaryExpr.Operator.LOGICAL_COMPLEMENT))
                            }else{
                                n.replace(UnaryExpr(nameExp,UnaryExpr.Operator.LOGICAL_COMPLEMENT))
                            }
                            return
                        }
                        val name = n.left.asBinaryExpr().left.toString()
                        if (n.right.asBinaryExpr().left.toString()
                                .equals(name) && vars.containsKey(
                                name
                            )
                        ) {
                            val init = vars.get(name)!!.getVariable(0).initializer
                            if (init.isPresent.not()) return
                            n.replace(init.get())
                            garbageCleaner.cleanParent(vars.get(name)!!)
                            vars.remove(name)
                            return
                        }
                        var result = getIncludeAssignExprNameAndTarget(n.left)
                        if (result.size > 0 && vars.containsKey(result[0].asNameExpr().nameAsString)) {
                            n.replace(result[1])
                            garbageCleaner.cleanParent(vars.get(result[0].asNameExpr().nameAsString)!!)
                            vars.remove(result[0].asNameExpr().nameAsString)
                            return
                        }
                        result = getIncludeAssignExprNameAndTarget(n.right)
                        if (result.size > 0 && vars.containsKey(result[0].asNameExpr().nameAsString) && n.parentNode.get().javaClass.equals(
                                BinaryExpr::class.java
                            )
                        ) {
                            val parent = n.parentNode.get() as BinaryExpr
                            if (parent.right.isBinaryExpr && parent.right.asBinaryExpr().operator.equals(
                                    BinaryExpr.Operator.EQUALS
                                ) && parent.right.asBinaryExpr().left.isNameExpr && parent.right.asBinaryExpr().left.asNameExpr().nameAsString.equals(
                                    result[0].asNameExpr().nameAsString
                                ) && parent.right.asBinaryExpr().right.isNameExpr && parent.right.asBinaryExpr().right.asNameExpr().nameAsString.equals(
                                    "undefined"
                                )
                            ) {
                                parent.replace(
                                    BinaryExpr(
                                        n.left,
                                        result[1],
                                        BinaryExpr.Operator.OR
                                    )
                                )
                                garbageCleaner.cleanParent(vars.get(result[0].asNameExpr().nameAsString)!!)
                                vars.remove(result[0].asNameExpr().nameAsString)
                            }
                        }
                    }
                }
            }
        }.visit(method, null)
        garbageCleaner.clean()
        if (log.contains(method.nameAsString)) System.out.println("optimizeBinaryExpr:\n"+ method.toString())
    }

    private fun getIncludeAssignExprNameAndTarget(exp: Expression): Array<Expression> {
        return if (exp.isBinaryExpr && exp.asBinaryExpr().operator.equals(BinaryExpr.Operator.EQUALS) && exp.asBinaryExpr().left.isEnclosedExpr && exp.asBinaryExpr().right.isNullLiteralExpr && exp.asBinaryExpr().left.asEnclosedExpr().inner.isAssignExpr && exp.asBinaryExpr().left.asEnclosedExpr().inner.asAssignExpr().target.isNameExpr) arrayOf(
            exp.asBinaryExpr().left.asEnclosedExpr().inner.asAssignExpr().target,
            exp.asBinaryExpr().left.asEnclosedExpr().inner.asAssignExpr().value
        ) else arrayOf()
    }

    fun optimizeVarDef() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: VariableDeclarator, arg: Any?) {
                super.visit(n, arg)
                if (Utils.isJsType(n.typeAsString).not()) n.setType("var")
            }
        }.visit(method, null)
    }

    fun optimizeUnaryExpr() {
        object : VoidVisitorAdapter<Any>() {
            override fun visit(n: AssignExpr, arg: Any?) {
                super.visit(n, arg)
                if (n.operator.equals(AssignExpr.Operator.ASSIGN) && n.target.isNameExpr) {
                    val name = n.target.asNameExpr().nameAsString
                    if (n.value.isBinaryExpr && n.value.asBinaryExpr().operator.equals(BinaryExpr.Operator.PLUS)) {
                        val binaryExpr = n.value.asBinaryExpr()
                        if ((binaryExpr.left.isNameExpr && binaryExpr.left.asNameExpr().nameAsString.equals(
                                name
                            ) && binaryExpr.right.isIntegerLiteralExpr && binaryExpr.right.asIntegerLiteralExpr()
                                .asNumber()
                                .toInt() == 1) || (binaryExpr.right.isNameExpr && binaryExpr.right.asNameExpr().nameAsString.equals(
                                name
                            ) && binaryExpr.left.isIntegerLiteralExpr && binaryExpr.left.asIntegerLiteralExpr()
                                .asNumber().toInt() == 1)
                        ) {
                            n.replace(
                                UnaryExpr(
                                    NameExpr(name),
                                    UnaryExpr.Operator.POSTFIX_INCREMENT
                                )
                            )
                        }
                    }
                }
            }
        }.visit(method, null)
        if (log.contains(method.nameAsString)) System.out.println("optimizeUnaryExpr:\n"+ method.toString())
    }

}