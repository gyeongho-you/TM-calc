package net.g1project.tmcalc

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 도감 = 앱에 들어 있는 기본 도감(assets/pets.json, 엑셀 시트) + 사용자가 앱에서 추가/수정한 "내 도감".
 * 내 도감은 폰의 앱 저장소(files/pets_user.json)에 따로 저장되어 앱을 업데이트해도 남는다.
 * 이름이 같으면 내 도감이 기본 도감을 덮어쓴다.
 */
object PetStore {

    private const val USER_FILE = "pets_user.json"

    /** 내 도감이 바뀔 때마다 올라간다. 레이어가 이 값을 보고 도감을 다시 읽는다. */
    @Volatile
    var version = 0
        private set

    fun builtIn(ctx: Context): List<Pet> =
        PetDb.parse(ctx.assets.open("pets.json").bufferedReader().use { it.readText() }).pets

    fun user(ctx: Context): List<Pet> {
        val f = File(ctx.filesDir, USER_FILE)
        if (!f.exists()) return emptyList()
        return runCatching { PetDb.parse(f.readText()).pets }.getOrDefault(emptyList())
    }

    /** 기본 도감에 내 도감을 덮어쓴 결과 */
    fun load(ctx: Context): PetDb = PetDb(merge(builtIn(ctx), user(ctx)))

    fun merge(base: List<Pet>, user: List<Pet>): List<Pet> {
        val mine = user.associateBy { PetDb.key(it.name) }
        return base.map { mine[PetDb.key(it.name)] ?: it } +
            user.filter { u -> base.none { PetDb.key(it.name) == PetDb.key(u.name) } }
    }

    /** 내 도감에 추가하거나 같은 이름을 고친다. 이름을 바꿔 고친 경우 [oldName] 줄은 지운다. */
    fun put(ctx: Context, pet: Pet, oldName: String? = null) {
        val drop = setOfNotNull(PetDb.key(pet.name), oldName?.let { PetDb.key(it) })
        save(ctx, user(ctx).filter { PetDb.key(it.name) !in drop } + pet)
    }

    /** 내 도감에서 지운다 (기본 도감에 있던 소환수면 기본값으로 돌아간다). */
    fun remove(ctx: Context, name: String) {
        save(ctx, user(ctx).filter { PetDb.key(it.name) != PetDb.key(name) })
    }

    /** 가져오기 결과: 새로 추가, 값이 바뀜, 지금과 같아서 그대로 */
    class ImportResult(val added: Int, val changed: Int, val same: Int)

    /**
     * JSON 파일(내보내기 형식 또는 앱 내장 형식)의 소환수들을 내 도감에 합친다.
     * 전체 도감을 내보내서 몇 줄만 고쳐 올려도 되도록, 기본 도감과 값이 같은 줄은 내 도감에 넣지 않는다
     * (그 소환수를 내 도감에서 고쳐 뒀었다면 기본값으로 돌아간다).
     */
    fun import(ctx: Context, json: String): ImportResult {
        val pets = PetDb.parse(json).pets
        val base = builtIn(ctx).associateBy { PetDb.key(it.name) }
        val current = load(ctx)
        var added = 0; var changed = 0; var same = 0
        for (p in pets) {
            val now = current.find(p.name)
            when {
                now == null -> added++
                sameValues(now, p) -> same++
                else -> changed++
            }
        }
        val keys = pets.map { PetDb.key(it.name) }.toSet()
        val mine = pets.filter { p -> base[PetDb.key(p.name)]?.let { !sameValues(it, p) } ?: true }
        save(ctx, user(ctx).filter { PetDb.key(it.name) !in keys } + mine)
        return ImportResult(added, changed, same)
    }

    private fun sameValues(a: Pet, b: Pet) =
        a.init.contentEquals(b.init) && a.sGrowth.contentEquals(b.sGrowth) && a.maxS.contentEquals(b.maxS) &&
            a.grade == b.grade && a.element == b.element && a.type == b.type

    /**
     * 내보내기 형식: 한 줄에 소환수 하나, 한글 키. PC 메모장/엑셀에서 고치기 쉽게.
     * [{"이름":"라르곤","등급":"전설","속성":"불","타입":"야수","초기치":[14,9,10,114],"S성장률":[3.54,2.32,2.57,27.67],"만렙S":[541,356,394,4237]}, ...]
     * 배열 순서는 항상 공, 방, 순, 체.
     */
    fun exportJson(pets: List<Pet>): String = pets.joinToString(",\n", "[\n", "\n]\n") { p ->
        "{\"이름\":${JSONObject.quote(p.name)},\"등급\":${JSONObject.quote(p.grade)}," +
            "\"속성\":${JSONObject.quote(p.element)},\"타입\":${JSONObject.quote(p.type)}," +
            "\"초기치\":[${p.init.joinToString(",")}],\"S성장률\":[${p.sGrowth.joinToString(",")}]," +
            "\"만렙S\":[${p.maxS.joinToString(",")}]}"
    }

    private fun save(ctx: Context, pets: List<Pet>) {
        File(ctx.filesDir, USER_FILE).writeText(exportJson(pets))
        version++
    }
}
