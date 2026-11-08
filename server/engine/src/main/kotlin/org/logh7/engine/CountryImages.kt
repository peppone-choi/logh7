package org.logh7.engine

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Editable image originals and bounded game thumbnails. Author: Peppone Choi (peppone-choi). */
enum class CountryImageKind(val key:String) { EMBLEM("emblem"), FLAG("flag"), MAP_ICON("map_icon"), PORTRAIT("portrait") }
data class CountryImageInfo(val key:String,val kind:CountryImageKind,val revision:Long,val title:String,val description:String,
    val sha256:String,val width:Int,val height:Int)
data class CountryImageFile(val info:CountryImageInfo,val png:ByteArray,val rgba:ByteArray)
class CountryImageUpload private constructor(val png:ByteArray,val rgba:ByteArray,val sha256:String,val width:Int,val height:Int) {
    companion object {
        const val MAX_BYTES=4*1024*1024
        fun decode(bytes:ByteArray):CountryImageUpload {
            require(bytes.size in 33..MAX_BYTES && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {"PNG required (up to 4 MiB)"}
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use {input->
                val reader=ImageIO.getImageReadersByFormatName("png").next()
                try {
                    reader.setInput(input,true,true)
                    val width=reader.getWidth(0);val height=reader.getHeight(0)
                    require(width in 1..2048 && height in 1..2048) {"PNG dimensions must be 1..2048"}
                    val source=reader.read(0)
                    val thumbnail=BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB)
                    val graphics=thumbnail.createGraphics()
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        val scale=minOf(32.0/width,32.0/height)
                        val w=maxOf(1,(width*scale).toInt());val h=maxOf(1,(height*scale).toInt())
                        graphics.drawImage(source,(32-w)/2,(32-h)/2,w,h,null)
                    } finally {graphics.dispose()}
                    val rgba=ByteArray(4096)
                    for(y in 0..31)for(x in 0..31) {
                        val color=thumbnail.getRGB(x,y);val offset=(y*32+x)*4
                        rgba[offset]=(color ushr 16).toByte();rgba[offset+1]=(color ushr 8).toByte()
                        rgba[offset+2]=color.toByte();rgba[offset+3]=(color ushr 24).toByte()
                    }
                    return CountryImageUpload(bytes.copyOf(),rgba,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},width,height)
                } finally {reader.dispose()}
            }
        }
    }
}
data class CountryImageSelection(val nativeEmblem:Int,val emblem:ByteArray?=null,val mapIcon:ByteArray?=null,
    val emblemKey:String?=null,val flagKey:String?=null,val mapIconKey:String?=null) {
    init {require(nativeEmblem in setOf(0,2,3));require(listOfNotNull(emblem,mapIcon).all {it.size==4096})}
}
data class CountryImageSnapshot(val revision:Long,val countries:Map<Int,CountryImageSelection>) {
    init {require(revision>=0 && countries.size<=350 && countries.keys.all {it>0})}
}
class PublishedCountryImages {
    @Volatile private var value=CountryImageSnapshot(0,emptyMap())
    @Synchronized fun publish(next:CountryImageSnapshot) {if(next.revision>=value.revision)value=next}
    fun current()=value
}
enum class CountryImageOutcome { CHANGED, UNCHANGED, CONFLICT, NOT_FOUND, INVALID }
interface CountryImageStore {
    fun list():List<CountryImageInfo>
    fun read(key:String,revision:Long?=null):CountryImageFile?
    fun history(key:String):List<CountryImageInfo>
    /** Revision 0 creates; a null upload edits metadata and preserves the file. */
    fun save(key:String,kind:CountryImageKind,expectedRevision:Long,title:String,description:String,upload:CountryImageUpload?):CountryImageOutcome
    fun restore(key:String,expectedRevision:Long,sourceRevision:Long):CountryImageOutcome
    /** Null clears this slot; it never changes territory, characters or ship models. */
    fun bind(country:Int,expectedDefinitionsRevision:Long,slot:CountryImageKind,key:String?):CountryImageOutcome
    fun snapshot():CountryImageSnapshot
    companion object {
        fun validKey(key:String)=key.matches(Regex("[a-z][a-z0-9_]{0,39}"))
        fun validText(title:String,description:String)=title.isNotBlank() && title.length<=80 && title.none {it.isISOControl() || it.isSurrogate()} &&
            description.length<=1000 && description.none {(it.isISOControl() && it!='\n' && it!='\r' && it!='\t') || it.isSurrogate()}
    }
}
