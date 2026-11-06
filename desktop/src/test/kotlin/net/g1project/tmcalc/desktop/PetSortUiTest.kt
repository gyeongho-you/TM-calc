package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Pet
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** 도감 관리: 정렬을 '공격 높은순'으로 고르면 만렙S 공격 순서로 바뀐다 */
class PetSortUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun sortByAttack() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        lateinit var dlg: PetEditorDialog
        SwingUtilities.invokeAndWait { dlg = PetEditorDialog(JFrame(), DesktopPets(tmp.newFolder())) {}; dlg.isVisible = true }
        val box = PetEditorDialog::class.java.getDeclaredField("sortBox").apply { isAccessible = true }.get(dlg) as JComboBox<*>
        SwingUtilities.invokeAndWait { box.selectedIndex = 3 } // 공격 높은순
        Thread.sleep(300)
        @Suppress("UNCHECKED_CAST")
        val model = PetEditorDialog::class.java.getDeclaredField("model").apply { isAccessible = true }.get(dlg) as DefaultListModel<Pet>
        val atk = (0 until model.size).map { model[it].maxS[0] }
        assertEquals(atk.sortedDescending(), atk)
        val img = BufferedImage(dlg.width, dlg.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { dlg.paint(img.graphics) }
        File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/dex-sort.png"))
        SwingUtilities.invokeAndWait { dlg.dispose() }
    }
}
