import com.github.javaparser.ast.Node

class GarbageCleaner {
    private val parent=ArrayList<Node>()
    private val me=ArrayList<Node>()

    fun cleanParent(node:Node){
        parent.add(node)
    }

    fun cleanMe(node: Node){
        me.add(node)
    }

    fun clean(){
        for (node in parent) {
            node.parentNode.get().remove()
        }
        for (node in me) {
            node.remove()
        }
        parent.clear()
        me.clear()
    }
}