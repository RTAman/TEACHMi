"""Fail-closed checks for the verification harness; do not start Docker."""
import copy
import unittest
import run

class SafetyTests(unittest.TestCase):
    def host(self):
        return {"Memory":536870912,"MemorySwap":536870912,"NanoCpus":100000000,
                "PortBindings":{},"RestartPolicy":{"Name":"no"}}
    def test_rejects_relaxed_resources_and_public_ports(self):
        self.assertTrue(run.validate_resources(self.host()))
        for key,value in (("Memory",2147483648),("MemorySwap",1073741824),
                          ("NanoCpus",0),("PortBindings",{"8080/tcp":[{"HostPort":"8080"}]}),
                          ("RestartPolicy",{"Name":"unless-stopped"})):
            candidate=self.host();candidate[key]=value
            self.assertFalse(run.validate_resources(candidate),key)
    def test_requires_real_cgroup_cpu_limit_and_no_swap(self):
        sample={"memoryMax":536870912,"swapMax":0,"cpuMax":"10000 100000"}
        self.assertTrue(run.validate_cgroup(sample))
        for key,value in (("memoryMax",1073741824),("swapMax",536870912),("cpuMax","max 100000"),("cpuMax","100000 100000")):
            changed=dict(sample);changed[key]=value
            self.assertFalse(run.validate_cgroup(changed),key)
    def test_cleanup_cannot_target_existing_projects(self):
        owned="teachmi-memory512-"+"a"*16
        self.assertTrue(run.validate_ownership(owned,owned))
        for project,label in ((owned,"teachmi-prod"),("teachmi-prod","teachmi-prod"),(owned,""),("teachmi-memory512-old","teachmi-memory512-old")):
            self.assertFalse(run.validate_ownership(project,label))

if __name__=="__main__":
    unittest.main()
