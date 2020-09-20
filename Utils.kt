import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.SwitchStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.utils.StringEscapeUtils
import java.util.function.BinaryOperator

class Utils {
    companion object {

        fun exp2type(exp: Expression): ClassOrInterfaceType {
            return if (exp.isNameExpr) ClassOrInterfaceType(
                null,
                exp.asNameExpr().nameAsString
            ) else ClassOrInterfaceType(
                exp2type(exp.asFieldAccessExpr().scope), exp.asFieldAccessExpr().nameAsString
            )
        }

        fun parseSwitch(stmt: SwitchStmt): HashMap<Int, NodeList<Expression>> {
            val map = HashMap<Int, NodeList<Expression>>()
            val labels = ArrayList<Int>()
            for (entry in stmt.entries) {
                if (entry.statements.isEmpty()) {
                    labels.add(entry.labels.get(0).asIntegerLiteralExpr().asNumber().toInt())
                } else {
                    val list = NodeList<Expression>()
                    val statement = entry.statements.get(0)
                    if (statement.isSwitchStmt) {
                        for (switchEntry in statement.asSwitchStmt().entries) {
                            if (switchEntry.labels.isEmpty())
                                list.add(
                                    0,
                                    switchEntry.statements.get(0).asReturnStmt().expression.get()
                                )
                            else
                                list.add(
                                    switchEntry.statements.get(0).asReturnStmt().expression.get()
                                )
                        }
                    } else {
                        list.add(statement.asReturnStmt().expression.get())
                    }
                    if (labels.isNotEmpty()) {
                        for (label in labels) {
                            map.put(label, list)
                        }
                        labels.clear()
                    }
                    map.put(
                        if (entry.labels.isEmpty()) 0 else entry.labels.get(0)
                            .asIntegerLiteralExpr().asNumber().toInt(), list
                    )
                }
            }
            return map
        }

        fun getAllIf(ifStmt: IfStmt): NodeList<IfStmt> {
            val list = NodeList<IfStmt>()
            var thisStmt: Statement = ifStmt
            while (true) {
                if (thisStmt.isIfStmt) {
                    list.add(thisStmt.asIfStmt())
                    if (thisStmt.asIfStmt().elseStmt.isPresent.not()) break
                    thisStmt = thisStmt.asIfStmt().elseStmt.get()
                } else {
                    break
                }
            }
            return list
        }

        fun <T> getParent(node: Node, clazz: Class<T>): T {
            var node = node
            while (!node.javaClass.equals(clazz)) node = node.parentNode.get()
            return node as T
        }

        fun getIndex(block: BlockStmt, dest: Node): Int {
            return block.statements.indexOf(dest)
        }

        fun replaceOnBlockStmt(replaced: Statement, replacement: Statement) {
            if (replaced.parentNode.get().javaClass.equals(BlockStmt::javaClass)) {
                val statements = (replaced.parentNode.get() as BlockStmt).statements
                for ((index, statement) in statements.withIndex()) {
                    if (statement.equals(replaced)) {
                        statements.removeAt(index)
                        statements.add(index, replacement)
                    }
                }
            }
        }

        fun eval(str: String): MethodCallExpr {
            return MethodCallExpr("eval", StringLiteralExpr(StringEscapeUtils.escapeJava(str)))
        }

        fun getMethodBody(declaration: MethodDeclaration): String {
            val list = arrayListOf<String>()
            if (declaration.body.isPresent.not()) return ""
            for (statement in declaration.body.get().statements) {
                list.add(statement.toString())
            }
            return list.joinToString("\n").replace(Regex("\\} catch \\(Exception "), "} catch (")
        }

        fun parseToFunction(declaration: MethodDeclaration) {
            object : VoidVisitorAdapter<Any>() {
                override fun visit(n: ObjectCreationExpr, arg: Any?) {
                    super.visit(n, arg)
                    if (n.typeAsString.equals(Main.className)) {
                        val infos = Main.functionInfos.get(
                            n.getArgument(2).asIntegerLiteralExpr().asNumber().toInt()
                        )!!
                        val destMethod = infos.get(Main.functionInfo.METHOD) as MethodDeclaration
                        n.replace(
                            eval(
                                "function " + infos.get(Main.functionInfo.NAME) + "(" + (infos.get(
                                    Main.functionInfo.PARAMS
                                ) as List<String>).joinToString() + "){\n" + getMethodBody(
                                    destMethod
                                ) + "\n}"
                            )
                        )
                    }
                }
            }.visit(declaration, null)
        }

        fun parseThisToFunction(declaration: MethodDeclaration): MethodCallExpr {
            return eval("function(){\n" + getMethodBody(declaration) + "\n}")
        }

        fun getObjArrValue(parser: Parser, objArr: Expression): NodeList<Expression> {
            if (objArr.isArrayCreationExpr) {
                if (objArr.asArrayCreationExpr().initializer.isPresent) {
                    val list = objArr.asArrayCreationExpr().initializer.get().values
                    for (expression in list) {
                        expression.replace(parser.checkThis(parser.parse(expression)))
                    }
                    return list
                }
            } else if (objArr.isArrayInitializerExpr) {
                val list = objArr.asArrayInitializerExpr().values
                for (expression in list) {
                    expression.replace(parser.checkThis(parser.parse(expression)))
                }
                return list
            }
            return NodeList()
        }

        fun replaceNameExpr(name:String,exp:Expression,node:MethodDeclaration){
            object :VoidVisitorAdapter<Any>(){
                override fun visit(n: NameExpr, arg: Any?) {
                    super.visit(n, arg)
                    if(n.nameAsString.equals(name))n.replace(exp)
                }
            }.visit(node,null)
        }

        fun optimizeBinaryExpr(binaryExpr: BinaryExpr):String {
            val left=if(binaryExpr.left.isStringLiteralExpr)binaryExpr.left.asStringLiteralExpr().asString() else if(binaryExpr.left.isBinaryExpr) optimizeBinaryExpr(binaryExpr.left.asBinaryExpr()) else ""
            val right=if(binaryExpr.right.isStringLiteralExpr)binaryExpr.right.asStringLiteralExpr().asString() else if(binaryExpr.right.isBinaryExpr) optimizeBinaryExpr(binaryExpr.right.asBinaryExpr()) else ""
            return left+right
        }

        fun getVarName(exp: Expression): String {
            if (exp.isAssignExpr) {
                return exp.asAssignExpr().target.toString()
            } else if (exp.isVariableDeclarationExpr) {
                return exp.asVariableDeclarationExpr().getVariable(0).nameAsString
            } else {
                return ""
            }
        }

        fun getDefVarName(exp: Expression): String {
            if (exp.isUnaryExpr) {
                return exp.asUnaryExpr().expression.toString()
            } else if (exp.isAssignExpr && exp.asAssignExpr().operator.equals(AssignExpr.Operator.ASSIGN)) {
                return exp.asAssignExpr().target.toString()
            } else {
                return ""
            }
        }

        fun getParentBlockStmt(node: Node): BlockStmt {
            var node = node
            while (!node.javaClass.equals(BlockStmt::class.java)) {
                if (node.parentNode.isPresent.not()) break
                node = node.parentNode.get()
            }
            return node as BlockStmt
        }

        fun hasChild(node:Node,child:Node):Boolean{
            for (childNode in node.childNodes) {
                if(childNode.equals(child))return true
                if(childNode.childNodes.isNotEmpty()&& hasChild(childNode,child))return true
            }
            return false
        }

        fun getConditions(exp:Expression,operator: BinaryExpr.Operator):ArrayList<Expression>{
            if(exp.isBinaryExpr){
                if(exp.asBinaryExpr().operator.equals(operator).not())return arrayListOf()
                val ret= arrayListOf<Expression>()
                val left=getConditions(exp.asBinaryExpr().left,operator)
                val right=getConditions(exp.asBinaryExpr().right,operator)
                if(left.size==0||right.size==0){
                    return arrayListOf()
                }else {
                    ret.addAll(left)
                    ret.addAll(right)
                    return ret
                }
            }else{
                return arrayListOf(exp)
            }
        }

        fun isJsType(str:String):Boolean{
            return str.equals("var")||str.equals("const")
        }
    }
}