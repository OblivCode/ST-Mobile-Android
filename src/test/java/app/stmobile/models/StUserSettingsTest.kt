package app.stmobile.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StUserSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDefaultValuesMatchVanillaSillyTavern() {
        val settings = StUserSettings.fromJson("{}")

        assertFalse("autoLoadChat default should be false", settings.autoLoadChat)
        assertFalse("autoConnect default should be false", settings.autoConnect)
        assertTrue("compactInputArea default should be true", settings.compactInputArea)
        assertEquals("sendOnEnter default should be AUTO (0)", StUserSettings.SEND_ON_ENTER_AUTO, settings.sendOnEnter)
        assertTrue("fastUiMode default should be true", settings.fastUiMode)
        assertFalse("reducedMotion default should be false", settings.reducedMotion)
        assertEquals("fontScale default should be 1.0f", 1.0f, settings.fontScale, 0.001f)
        assertEquals("uiLanguage default should be empty string", "", settings.uiLanguage)

        assertFalse("smoothStreaming default should be false", settings.smoothStreaming)
        assertEquals("streamingFps default should be 30", 30, settings.streamingFps)
        assertTrue("autoScrollChatToBottom default should be true", settings.autoScrollChatToBottom)
        assertEquals("chatWidth default should be 50", 50, settings.chatWidth)
        assertTrue("confirmMessageDelete default should be true", settings.confirmMessageDelete)
        assertFalse("clickToEdit default should be false", settings.clickToEdit)
        assertFalse("showSwipeNumAllMessages default should be false", settings.showSwipeNumAllMessages)
        assertTrue("restoreUserInput default should be true", settings.restoreUserInput)
        assertFalse("neverResizeAvatars default should be false", settings.neverResizeAvatars)
        assertEquals("avatarStyle default should be ROUND (0)", StUserSettings.AVATAR_STYLE_ROUND, settings.avatarStyle)
        assertTrue("timestampsEnabled default should be true", settings.timestampsEnabled)
    }

    @Test
    fun testParseExistingSettingsAndPreserveUnknownKeys() {
        val sampleJson = """
            {
                "username": "TestUser",
                "api_server": "http://127.0.0.1:5000",
                "tags": ["favorite", "rpg"],
                "custom_root_field": { "sub_key": 42 },
                "power_user": {
                    "theme": "Default (Dark)",
                    "blur_strength": 10,
                    "chat_width": 55,
                    "fast_ui_mode": true,
                    "font_scale": 1.1,
                    "send_on_enter": 0,
                    "auto_load_chat": false,
                    "ui_language": "zh-cn"
                }
            }
        """.trimIndent()

        val settings = StUserSettings.fromJson(sampleJson)

        // Verify parsed values
        assertEquals("TestUser", settings.raw().getString("username"))
        assertEquals("http://127.0.0.1:5000", settings.raw().getString("api_server"))
        assertEquals(true, settings.fastUiMode)
        assertEquals(1.1f, settings.fontScale, 0.001f)
        assertEquals(0, settings.sendOnEnter)
        assertEquals(false, settings.autoLoadChat)
        assertEquals("zh-cn", settings.uiLanguage)

        // Mutate settings fields
        settings.autoLoadChat = true
        settings.autoConnect = true
        settings.compactInputArea = false
        settings.sendOnEnter = StUserSettings.SEND_ON_ENTER_ENABLED
        settings.fastUiMode = false
        settings.reducedMotion = true
        settings.fontScale = 1.35f
        settings.uiLanguage = "ru-ru"

        // Serialize and re-read
        val dumped = settings.raw().toString()
        val reloaded = StUserSettings.fromJson(dumped)

        // Verify mutations persisted
        assertTrue(reloaded.autoLoadChat)
        assertTrue(reloaded.autoConnect)
        assertFalse(reloaded.compactInputArea)
        assertEquals(StUserSettings.SEND_ON_ENTER_ENABLED, reloaded.sendOnEnter)
        assertFalse(reloaded.fastUiMode)
        assertTrue(reloaded.reducedMotion)
        assertEquals(1.35f, reloaded.fontScale, 0.001f)
        assertEquals("ru-ru", reloaded.uiLanguage)

        // Verify surrounding root and power_user properties are 100% intact
        assertEquals("TestUser", reloaded.raw().getString("username"))
        assertEquals("http://127.0.0.1:5000", reloaded.raw().getString("api_server"))
        assertEquals(2, reloaded.raw().getJSONArray("tags").length())
        assertEquals(42, reloaded.raw().getJSONObject("custom_root_field").getInt("sub_key"))

        val powerUser = reloaded.raw().getJSONObject("power_user")
        assertEquals("Default (Dark)", powerUser.getString("theme"))
        assertEquals(10, powerUser.getInt("blur_strength"))
        assertEquals(55, powerUser.getInt("chat_width"))
        assertEquals("ru-ru", powerUser.getString("ui_language"))
    }

    @Test
    fun testSendOnEnterOptionsAndConstants() {
        val settings = StUserSettings.fromJson("{}")

        settings.sendOnEnter = StUserSettings.SEND_ON_ENTER_DISABLED
        assertEquals(-1, settings.sendOnEnter)

        settings.sendOnEnter = StUserSettings.SEND_ON_ENTER_AUTO
        assertEquals(0, settings.sendOnEnter)

        settings.sendOnEnter = StUserSettings.SEND_ON_ENTER_ENABLED
        assertEquals(1, settings.sendOnEnter)
    }

    @Test
    fun testFontScaleClampingAndNanResistance() {
        val settings = StUserSettings.fromJson("{}")

        // Lower bound clamp (min: 0.5)
        settings.fontScale = 0.1f
        assertEquals(0.5f, settings.fontScale, 0.001f)

        // Upper bound clamp (max: 1.5)
        settings.fontScale = 2.5f
        assertEquals(1.5f, settings.fontScale, 0.001f)

        // Valid mid value
        settings.fontScale = 1.25f
        assertEquals(1.25f, settings.fontScale, 0.001f)

        // NaN resistance defaults to 1.0f
        settings.fontScale = Float.NaN
        assertEquals(1.0f, settings.fontScale, 0.001f)
    }

    @Test
    fun testAtomicFileSaveAndReload() {
        val targetFile = File(tempFolder.root, "data/default-user/settings.json")
        val initialJson = """
            {
                "username": "SavedUser",
                "power_user": {
                    "fast_ui_mode": true
                }
            }
        """.trimIndent()

        val settings = StUserSettings.fromJson(initialJson, targetFile)
        settings.fastUiMode = false
        settings.autoLoadChat = true
        settings.save()

        assertTrue("settings.json must exist after save", targetFile.exists())

        val reloaded = StUserSettings.fromFile(targetFile)
        assertFalse(reloaded.fastUiMode)
        assertTrue(reloaded.autoLoadChat)
        assertEquals("SavedUser", reloaded.raw().getString("username"))
    }

    @Test
    fun testFromFileOrDefault_UserFileAbsent_UsesTemplateAndBindsDestination() {
        val userFile = File(tempFolder.root, "user/data/default-user/settings.json")
        val templateFile = File(tempFolder.root, "st/default/content/settings.json")
        templateFile.parentFile?.mkdirs()

        val templateJson = """
            {
                "username": "TemplateUser",
                "power_user": {
                    "fast_ui_mode": true,
                    "font_scale": 1,
                    "send_on_enter": 0
                }
            }
        """.trimIndent()
        templateFile.writeText(templateJson, Charsets.UTF_8)

        assertFalse("User file must not exist before test", userFile.exists())

        // Load using fallback
        val settings = StUserSettings.fromFileOrDefault(userFile, templateFile)

        // Verify loaded from template
        assertEquals("TemplateUser", settings.raw().getString("username"))
        assertTrue(settings.fastUiMode)
        assertEquals(userFile, settings.sourceFile)

        // Mutate and save
        settings.autoConnect = true
        settings.save()

        // Verify user file was created and contains changes
        assertTrue("User file must exist after save", userFile.exists())
        val userFileReloaded = StUserSettings.fromFile(userFile)
        assertTrue(userFileReloaded.autoConnect)
        assertEquals("TemplateUser", userFileReloaded.raw().getString("username"))

        // Verify template file was untouched
        val templateFileReloaded = StUserSettings.fromFile(templateFile)
        assertFalse("Template file must remain unmodified", templateFileReloaded.raw().getJSONObject("power_user").has("auto_connect"))
    }

    @Test
    fun testFromFileOrDefault_UserFilePresent_IgnoresTemplate() {
        val userFile = File(tempFolder.root, "settings_user.json")
        val templateFile = File(tempFolder.root, "settings_template.json")

        userFile.writeText("""{ "username": "RealUser" }""", Charsets.UTF_8)
        templateFile.writeText("""{ "username": "TemplateUser" }""", Charsets.UTF_8)

        val settings = StUserSettings.fromFileOrDefault(userFile, templateFile)
        assertEquals("RealUser", settings.raw().getString("username"))
    }

    @Test
    fun testParentDirectoryCreationOnSave() {
        val deepFile = File(tempFolder.root, "a/b/c/d/settings.json")
        assertFalse(deepFile.parentFile?.exists() == true)

        val settings = StUserSettings.fromJson("""{ "power_user": {} }""", deepFile)
        settings.autoLoadChat = true
        settings.save()

        assertTrue(deepFile.exists())
        assertTrue(deepFile.parentFile?.exists() == true)
    }

    @Test
    fun testEmptyOrMalformedJsonGracefulHandling() {
        val emptySettings = StUserSettings.fromJson("")
        assertFalse(emptySettings.autoLoadChat)
        assertTrue(emptySettings.compactInputArea)

        val malformedSettings = StUserSettings.fromJson("invalid json {{[[")
        assertFalse(malformedSettings.autoLoadChat)
        assertEquals(1.0f, malformedSettings.fontScale, 0.001f)
    }

    @Test
    fun testMissingPowerUserObjectCreatedOnDemand() {
        // Document without power_user
        val settings = StUserSettings.fromJson("""{ "username": "NoPowerUser" }""")
        assertFalse(settings.raw().has("power_user"))

        // Mutating a user settings property creates power_user
        settings.reducedMotion = true
        assertTrue(settings.raw().has("power_user"))
        assertTrue(settings.reducedMotion)
        assertEquals("NoPowerUser", settings.raw().getString("username"))
    }

    @Test
    fun testMobileSettingsClampingAndPersistence() {
        val targetFile = tempFolder.newFile("settings.json")
        val settings = StUserSettings.fromJson("""{ "power_user": {} }""", targetFile)

        // Test clamping on write
        settings.streamingFps = 1000
        assertEquals(100, settings.streamingFps)
        settings.streamingFps = 1
        assertEquals(5, settings.streamingFps)
        settings.streamingFps = 60
        assertEquals(60, settings.streamingFps)

        settings.chatWidth = 10
        assertEquals(25, settings.chatWidth)
        settings.chatWidth = 200
        assertEquals(100, settings.chatWidth)
        settings.chatWidth = 75
        assertEquals(75, settings.chatWidth)

        settings.avatarStyle = -5
        assertEquals(0, settings.avatarStyle)
        settings.avatarStyle = 99
        assertEquals(3, settings.avatarStyle)
        settings.avatarStyle = 2
        assertEquals(2, settings.avatarStyle)

        settings.sendOnEnter = -99
        assertEquals(-1, settings.sendOnEnter)
        settings.sendOnEnter = 99
        assertEquals(1, settings.sendOnEnter)

        settings.smoothStreaming = true
        settings.autoScrollChatToBottom = false
        settings.confirmMessageDelete = false
        settings.clickToEdit = true
        settings.showSwipeNumAllMessages = true
        settings.restoreUserInput = false
        settings.neverResizeAvatars = true
        settings.timestampsEnabled = false

        settings.save()

        // Reload and verify
        val reloaded = StUserSettings.fromFile(targetFile)
        assertEquals(60, reloaded.streamingFps)
        assertEquals(75, reloaded.chatWidth)
        assertEquals(2, reloaded.avatarStyle)
        assertEquals(1, reloaded.sendOnEnter)
        assertTrue(reloaded.smoothStreaming)
        assertFalse(reloaded.autoScrollChatToBottom)
        assertFalse(reloaded.confirmMessageDelete)
        assertTrue(reloaded.clickToEdit)
        assertTrue(reloaded.showSwipeNumAllMessages)
        assertFalse(reloaded.restoreUserInput)
        assertTrue(reloaded.neverResizeAvatars)
        assertFalse(reloaded.timestampsEnabled)
    }
}
