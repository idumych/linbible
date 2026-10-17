rootProject.name = "linbible"

// JSword (AndBible fork) is used straight from the upstream And Bible checkout.
include(":jsword")
project(":jsword").projectDir = file("../upstream/jsword")
