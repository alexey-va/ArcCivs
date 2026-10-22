package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.context.MutableContextSet
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.net.URLClassLoader

class CivsChunkPermissionsTest : FunSpec({
    test("optional provider absence is safe during cache and listener method discovery") {
        val names = listOf(CivsChunkPermissions::class.java.name, CivsChunkLoading::class.java.name)
        val location = CivsChunkPermissions::class.java.protectionDomain.codeSource.location
        object : URLClassLoader(arrayOf(location), CivsChunkPermissions::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("net.luckperms.")) throw ClassNotFoundException(name)
                if (name in names) synchronized(getClassLoadingLock(name)) {
                    return (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                }
                return super.loadClass(name, resolve)
            }
        }.use { loader -> names.forEach {
            loader.loadClass(it).declaredMethods.isNotEmpty() shouldBe true
            loader.loadClass(it).declaredConstructors.isNotEmpty() shouldBe true
        } }
    }

    test("only a fresh effective owner grant admits background work and denial revokes it") {
        val owner = UUID.randomUUID()
        var now = 100L
        var available = true
        val reads = mutableListOf<CompletableFuture<Boolean>>()
        val permissions = CivsChunkPermissions({ available }, { id, world ->
            id shouldBe owner; world shouldBe "vanilla"
            CompletableFuture<Boolean>().also(reads::add)
        }, { now })
        val subjects = listOf(owner to "vanilla")
        permissions.pulse(subjects)
        permissions.allowed(owner, "vanilla") shouldBe false
        reads.single().complete(true)
        permissions.pulse(subjects)
        permissions.allowed(owner, "vanilla") shouldBe true
        permissions.allowed(UUID.randomUUID(), "vanilla") shouldBe false
        permissions.allowed(owner, "survival") shouldBe false
        now += 15_000
        permissions.pulse(subjects)
        reads.last().complete(false)
        permissions.pulse(subjects)
        permissions.allowed(owner, "vanilla") shouldBe false
        now += 15_000
        permissions.pulse(subjects)
        reads.last().complete(true)
        permissions.pulse(subjects)
        available = false
        permissions.allowed(owner, "vanilla") shouldBe false
        permissions.close()
    }

    test("expired failed removed and pre-reload permission replies fail closed") {
        val owner = UUID.randomUUID()
        var now = 1L
        val reads = mutableListOf<CompletableFuture<Boolean>>()
        val permissions = CivsChunkPermissions({ true }, { _, _ -> CompletableFuture<Boolean>().also(reads::add) }, { now })
        val subjects = listOf(owner to "vanilla")
        permissions.pulse(subjects)
        reads[0].complete(true)
        permissions.pulse(subjects)
        now = 15_001
        permissions.pulse(subjects)
        now = 30_001
        permissions.allowed(owner, "vanilla") shouldBe false
        reads[1].completeExceptionally(IllegalStateException("provider unavailable"))
        permissions.pulse(subjects)
        permissions.allowed(owner, "vanilla") shouldBe false
        permissions.reset()
        reads.last().complete(true)
        permissions.pulse(subjects)
        permissions.allowed(owner, "vanilla") shouldBe false
        val fresh = reads.last()
        permissions.pulse(emptyList())
        fresh.complete(true)
        permissions.pulse(emptyList())
        permissions.allowed(owner, "vanilla") shouldBe false
        permissions.close()
    }

    test("permission hydration is bounded across all offline owners") {
        val reads = mutableListOf<CompletableFuture<Boolean>>()
        val permissions = CivsChunkPermissions({ true }, { _, _ -> CompletableFuture<Boolean>().also(reads::add) })
        val subjects = List(500) { UUID.randomUUID() to "vanilla" }
        repeat(10) { permissions.pulse(subjects) }
        reads.size shouldBe 4
        reads[0].complete(false)
        permissions.pulse(subjects)
        reads.size shouldBe 5
        permissions.close()
        reads.forEach { it.complete(true) }
        permissions.allowed(subjects[0].first, "vanilla") shouldBe false
    }

    test("LuckPerms checks inherited permissions in static server plus settlement world context and releases the loaded user") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CivsChunkPermissionsTest")
            val api = mockk<LuckPerms>()
            val contexts = mockk<ContextManager>()
            val staticContext = mockk<ImmutableContextSet>()
            val mutable = mockk<MutableContextSet>(relaxed = true)
            val cityContext = mockk<ImmutableContextSet>()
            val staticOptions = mockk<QueryOptions>()
            val builder = mockk<QueryOptions.Builder>()
            val cityOptions = mockk<QueryOptions>()
            val users = mockk<UserManager>(relaxed = true)
            val user = mockk<User>(relaxed = true)
            val permissions = mockk<CachedPermissionData>()
            val pending = CompletableFuture<User>()
            val owner = UUID.randomUUID()
            every { api.contextManager } returns contexts
            every { contexts.staticContext } returns staticContext
            every { staticContext.mutableCopy() } returns mutable
            every { mutable.immutableCopy() } returns cityContext
            every { contexts.staticQueryOptions } returns staticOptions
            every { staticOptions.toBuilder() } returns builder
            every { builder.context(cityContext) } returns builder
            every { builder.build() } returns cityOptions
            every { api.userManager } returns users
            every { users.loadUser(owner) } returns pending
            every { user.cachedData.getPermissionData(cityOptions) } returns permissions
            every { permissions.checkPermission("arccivs.chunkload") } returns Tristate.TRUE
            paper.server.servicesManager.register(LuckPerms::class.java, api, plugin, ServicePriority.Normal)
            val checked = CivsLuckPermsChunkPermission.check(plugin, owner, "vanilla")
            checked.isDone shouldBe false
            verify { mutable.removeAll("world"); mutable.add("world", "vanilla") }
            pending.complete(user)
            checked.join() shouldBe true
            verify(exactly = 1) { users.cleanupUser(user) }
            verify(exactly = 0) { contexts.getQueryOptions(any<Any>()) }
            paper.server.servicesManager.unregister(LuckPerms::class.java, api)
            CivsLuckPermsChunkPermission.check(plugin, owner, "vanilla").join() shouldBe false
        }
    }
})
