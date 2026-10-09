import json,unittest
from unittest.mock import Mock
from owned_resources import remove_owned,assert_backend_owner
class OwnedCleanupFaults(unittest.TestCase):
    def info(self,label='owner',volume=None):
        return json.dumps([{'Config':{'Labels':{'yshop.quality.owner':label}},'Mounts':[] if volume is None else [{'Type':'volume','Name':volume}]}])
    def test_owned_removed_and_verified(self):
        call=Mock(side_effect=['owned',self.info(),'','']);self.assertEqual(1,remove_owned(call,['owned'],'owner')['containersRemoved'])
    def test_wrong_owner_never_removed(self):
        call=Mock(side_effect=['owned',self.info('other')])
        with self.assertRaisesRegex(RuntimeError,'OWNERSHIP'):remove_owned(call,['owned'],'owner')
        self.assertEqual(2,call.call_count)
    def test_docker_outage_cannot_pass(self):
        with self.assertRaises(RuntimeError):remove_owned(Mock(side_effect=RuntimeError('unavailable')),['owned'],'owner')
    def test_rm_failure_cannot_pass(self):
        call=Mock(side_effect=['owned',self.info(),RuntimeError('remove failed')])
        with self.assertRaises(RuntimeError):remove_owned(call,['owned'],'owner')
    def test_container_remaining_cannot_pass(self):
        call=Mock(side_effect=['owned',self.info(),'','owned'])
        with self.assertRaisesRegex(RuntimeError,'NOT_DESTROYED'):remove_owned(call,['owned'],'owner')
    def test_volume_remaining_cannot_pass(self):
        call=Mock(side_effect=['owned',self.info(volume='a'*64),'','','a'*64])
        with self.assertRaisesRegex(RuntimeError,'VOLUME_NOT_DESTROYED'):remove_owned(call,['owned'],'owner')
    def test_named_unowned_volume_never_removed(self):
        call=Mock(side_effect=['owned',self.info(volume='shared-volume')])
        with self.assertRaisesRegex(RuntimeError,'ANONYMOUS'):remove_owned(call,['owned'],'owner')
        self.assertEqual(2,call.call_count)
    def test_exact_backend_owner_required_before_any_http_write(self):
        assert_backend_owner({'qualityOwner':'owner'},'owner')
        with self.assertRaisesRegex(RuntimeError,'OWNERSHIP'):assert_backend_owner({'qualityOwner':'different'},'owner')
    def test_unowned_health_response_cannot_admit_writes(self):
        for value in [None,{}, {'status':'UP'}]:
            with self.assertRaisesRegex(RuntimeError,'OWNERSHIP'):assert_backend_owner(value,'owner')
if __name__=='__main__':unittest.main()
