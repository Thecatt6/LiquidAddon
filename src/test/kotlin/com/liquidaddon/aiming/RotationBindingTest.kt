package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationBindingTest {

    // Fakes standing in for the two client builds. 0.40/0.41 want a ClientModule as the provider
    // of a rotation request, 0.42 widened it to EventListener; a module is an EventListener in
    // both, so the instance fits either door - only the compiled descriptor does not.
    interface FakeEventListener
    abstract class FakeClientModule : FakeEventListener
    class FakeModule : FakeClientModule()

    class FakePlan
    enum class FakePriority(val priority: Int) { IMPORTANT(20) }
    class Unrelated

    class Manager41 {
        var calls = 0
        var provider: Any? = null
        fun setRotationTarget(plan: FakePlan, priority: FakePriority, provider: FakeClientModule) {
            calls++
            this.provider = provider
        }
    }

    class Manager42 {
        var calls = 0
        var provider: Any? = null
        fun setRotationTarget(plan: FakePlan, priority: FakePriority, provider: FakeEventListener) {
            calls++
            this.provider = provider
        }
    }

    class ManagerRawInt {
        var calls = 0
        var priority = -1
        fun setRotationTarget(plan: FakePlan, priority: Int, provider: FakeEventListener) {
            calls++
            this.priority = priority
        }
    }

    class ManagerNoDoor {
        fun setRotationTarget(plan: Unrelated, priority: FakePriority, provider: FakeEventListener) = Unit
    }

    private fun pick(owner: Class<*>) = RotationBinding.pick(
        owner,
        planType = FakePlan::class.java.name,
        priorityType = FakePriority::class.java.name,
        provider = FakeModule::class.java,
    )

    @Test
    fun `a 0-41 client binds the ClientModule door and the call goes through`() {
        val binding = pick(Manager41::class.java) ?: throw AssertionError("the 0.41 overload must be found")
        assertEquals(false, binding.intPriority)

        val manager = Manager41()
        val module = FakeModule()
        binding.method.invoke(manager, FakePlan(), FakePriority.IMPORTANT, module)
        assertEquals(1, manager.calls)
        assertEquals(module, manager.provider)
    }

    @Test
    fun `a 0-42 client binds the EventListener door with the same instance`() {
        val binding = pick(Manager42::class.java) ?: throw AssertionError("the 0.42 overload must be found")
        assertEquals(false, binding.intPriority)

        val manager = Manager42()
        val module = FakeModule()
        binding.method.invoke(manager, FakePlan(), FakePriority.IMPORTANT, module)
        assertEquals(1, manager.calls)
        assertEquals(module, manager.provider, "the module is passed as the EventListener it is")
    }

    @Test
    fun `a client with only the raw int door binds it and says so`() {
        val binding = pick(ManagerRawInt::class.java) ?: throw AssertionError("the int overload is the fallback")
        assertEquals(true, binding.intPriority, "the caller has to hand over priority.priority")

        val manager = ManagerRawInt()
        binding.method.invoke(manager, FakePlan(), FakePriority.IMPORTANT.priority, FakeModule())
        assertEquals(1, manager.calls)
        assertEquals(20, manager.priority)
    }

    @Test
    fun `a client with no usable door binds nothing instead of guessing`() {
        assertNull(pick(ManagerNoDoor::class.java), "a plan type it does not take is not our door")
    }

    @Test
    fun `the supertype walk reaches interfaces above the superclass`() {
        val names = RotationBinding.supertypeNames(FakeModule::class.java)
        assertTrue(FakeModule::class.java.name in names)
        assertTrue(FakeClientModule::class.java.name in names, "the superclass")
        assertTrue(FakeEventListener::class.java.name in names, "the interface the superclass implements")
        assertTrue("java.lang.Object" in names)
    }
}
