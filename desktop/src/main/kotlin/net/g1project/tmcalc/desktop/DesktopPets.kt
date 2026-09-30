package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Pet
import net.g1project.tmcalc.PetDb
import org.json.JSONObject
import java.io.File

/**
 * PC 도감 = 기본 도감(pets.json, 폰 앱과 같은 파일) + 내 도감(%APPDATA%\TM-calc\pets_user.json).
 * 폰 앱의 PetStore 와 같은 규칙: 이름이 같으면 내 도감이 이기고, 파일 형식도 같아서 폰에서 저장한 JSON 을 그대로 올릴 수 있다.
 */
class DesktopPets(val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "TM-calc")) {

    private val userFile = File(dir, "pets_user.json")

    val builtIn: List<Pet> = PetDb.parse(javaClass.getResource("/pets.json")!!.readText()).pets
    private val builtInKeys = builtIn.associateBy { PetDb.key(it.name) }

    var db: PetDb = load()
        private set

    /** 도감이 바뀔 때마다 올라간다 (계산 창이 다시 계산하도록) */
    var version = 0
        private set

    fun user(): List<Pet> =
        if (!userFile.exists()) emptyList() else runCatching { PetDb.parse(userFile.readText()).pets }.getOrDefault(emptyList())

    private fun load(): PetDb {
        val mine = user().associateBy { PetDb.key(it.name) }
        val merged = builtIn.map { mine[PetDb.key(it.name)] ?: it } + mine.values.filter { PetDb.key(it.name) !in builtInKeys }
        return PetDb(merged)
    }

    fun isMine(name: String) = user().any { PetDb.key(it.name) == PetDb.key(name) }
    fun inBase(name: String) = PetDb.key(name) in builtInKeys

    /** 내 도감에 추가하거나 고친다. 이름을 바꿔 고쳤으면 [oldName] 줄은 지운다 */
    fun put(pet: Pet, oldName: String? = null) {
        val drop = setOfNotNull(PetDb.key(pet.name), oldName?.let { PetDb.key(it) })
        save(user().filter { PetDb.key(it.name) !in drop } + pet)
    }

    /** 내 도감에서 지운다 (기본 도감에 있던 소환수면 기본값으로 돌아간다) */
    fun remove(name: String) = save(user().filter { PetDb.key(it.name) != PetDb.key(name) })

    class ImportResult(val added: Int, val changed: Int, val same: Int)

    /**
     * JSON 파일(폰 앱 내보내기 형식 또는 앱 내장 형식)의 소환수를 내 도감에 합친다.
     * 전체 도감을 올려도 되도록, 기본 도감과 값이 같은 줄은 내 도감에 넣지 않는다. 형식이 틀리면 예외.
     */
    fun import(file: File): ImportResult {
        require(file.length() <= 1 shl 20) { "파일이 너무 큽니다 (1MB 이하)" }
        val pets = PetDb.parse(file.readText()).pets
        var added = 0; var changed = 0; var same = 0
        for (p in pets) {
            val now = db.find(p.name)
            when { now == null -> added++; sameValues(now, p) -> same++; else -> changed++ }
        }
        val keys = pets.map { PetDb.key(it.name) }.toSet()
        val mine = pets.filter { p -> builtInKeys[PetDb.key(p.name)]?.let { !sameValues(it, p) } ?: true }
        save(user().filter { PetDb.key(it.name) !in keys } + mine)
        return ImportResult(added, changed, same)
    }

    /** [all] 이면 기본 + 내 도감 전체, 아니면 내 도감만 */
    fun export(file: File, all: Boolean): Int {
        val pets = if (all) db.pets else user()
        file.writeText(toJson(pets))
        return pets.size
    }

    private fun sameValues(a: Pet, b: Pet) =
        a.init.contentEquals(b.init) && a.sGrowth.contentEquals(b.sGrowth) && a.maxS.contentEquals(b.maxS) &&
            a.grade == b.grade && a.element == b.element && a.type == b.type

    private fun save(pets: List<Pet>) {
        dir.mkdirs()
        userFile.writeText(toJson(pets))
        db = load()
        version++
    }

    /** 폰 앱 내보내기와 같은 형식: 한 줄에 소환수 하나, 한글 키 */
    private fun toJson(pets: List<Pet>) = pets.joinToString(",\n", "[\n", "\n]\n") { p ->
        "{\"이름\":${JSONObject.quote(p.name)},\"등급\":${JSONObject.quote(p.grade)}," +
            "\"속성\":${JSONObject.quote(p.element)},\"타입\":${JSONObject.quote(p.type)}," +
            "\"초기치\":[${p.init.joinToString(",")}],\"S성장률\":[${p.sGrowth.joinToString(",")}]," +
            "\"만렙S\":[${p.maxS.joinToString(",")}]}"
    }

    val userCount: Int get() = user().size
}
