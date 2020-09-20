import com.github.javaparser.utils.StringEscapeUtils

class Fixer {
    var content=StringBuilder()
    val strRange= arrayListOf<Array<Int>>()
    val strContent= arrayListOf<String>()
    var count=0
    constructor(string: String){
        content= StringBuilder(string)
        reset()
    }

    private fun reset(){
        strContent.clear()
        strRange.clear()
        var start=-1
        for (i in 0 until content.length){
            if(content.get(i).equals('"')&&(i-1<0||!content.get(i-1).equals('\\'))){
                if(start>=0){
                    strRange.add(arrayOf(start,i))
                    strContent.add(StringEscapeUtils.unescapeJava(content.substring(start+1,i)))
                    start=-1
                }else{
                    start=i
                }
            }
        }
        strRange.reverse()
        strContent.reverse()
    }

    fun getContent():String{
        return content.toString()
    }

    fun getAndResetCount():Int{
        val count=this.count
        this.count=0
        return count
    }

    fun expand(methodName:String,start:String){
        for(i in strContent.indices){
            if(strContent[i].startsWith(start)){
                val range=strRange[i]
                if(content.substring(range[0]-1-methodName.length,range[0]).equals(methodName+"(")){
                    content.replace(range[0]-1-methodName.length,range[1]+2+if(content.get(range[1]+2).equals(';'))1 else 0,strContent[i])
                    count++
                }
            }
        }
        reset()
    }
}