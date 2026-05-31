package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.script.lexer.HeaderLexer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoPinTypeTest {

    @Test fun `HeaderLexer parses Video to PinType VIDEO`() {
        val r = HeaderLexer.parse("""val screen = output<Video>("screen")""")
        assertEquals(listOf("screen" to PinType.VIDEO), r.outputs.map { it.id to it.type })
    }

    @Test fun `scriptPinType maps Video to ScriptType VIDEO`() {
        assertEquals(ScriptType.VIDEO, scriptPinType<Video>())
    }

    @Test fun `ScriptType VIDEO bridges to PinType VIDEO`() {
        assertEquals(PinType.VIDEO, ScriptType.VIDEO.toPinType())
    }
}
