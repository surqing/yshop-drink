<template>
  <el-drawer v-model="drawer" :title="dialogTitle" size="50%">
    <el-table :data="tableData" style="width: 100%">
      <el-table-column label="id" align="center" prop="id" />
      <el-table-column label="店铺名称" align="center" prop="shopName" width="150" />
      <el-table-column label="优惠券名称" align="center" prop="title" width="150" />
      <el-table-column label="消费多少可用" align="center" prop="least" width="150" />
      <el-table-column label="优惠券金额" align="center" prop="value" width="150" />
      <el-table-column label="可用类型" align="center" prop="type">
        <template #default="scope">
          <span v-if="scope.row.type == 1">自取</span>
          <span v-else-if="scope.row.type == 2">外卖</span>
          <span v-else>通用</span>
         </template>
      </el-table-column>
      <el-table-column label="会员编号" align="center" prop="userId" />
      <el-table-column label="权益状态" width="150"><template #default="scope">{{ labels[scope.row.reservationState] || '需审核' }}</template></el-table-column>
      <el-table-column
        label="领取时间"
        align="center"
        prop="createTime"
        :formatter="dateFormatter"
        width="170"
      />
      <el-table-column label="操作" align="center">
        <template #default="scope">
          <el-button
            link
            type="danger"
            @click="handleDelete(scope.row.id)"
            v-if="canInvalidate && ['AVAILABLE','NOT_YET_VALID','EXPIRED'].includes(scope.row.reservationState)"
          >
            显式作废
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>

</template>
<script setup lang="ts">
import * as UserApi from '@/api/mall/coupon/user'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { dateFormatter } from '@/utils/formatTime'

const canInvalidate = computed(() => useUserStore().getRoles.includes('super_admin') || useUserStore().getPermissions.includes('coupon:user:delete'))
const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗
const dialogTitle = ref('') // 弹窗的标题
const drawer = ref(false)
const tableData = ref<UserApi.UserVO[]>([])
const labels:Record<string,string>={AVAILABLE:'可用',RESERVED:'待付款预占',USED:'确认核销',EXPIRED:'已过期',NOT_YET_VALID:'尚未生效',INVALID:'已作废',REVIEW_REQUIRED:'需审核'}
const couponId = ref<number>()
/** 打开弹窗 */
const open = async (type: string, id?: number) => {
  drawer.value = true
  dialogTitle.value = t('action.' + type)
  couponId.value = id
  await getList(id)
}
defineExpose({ open }) // 提供 open 方法，用于打开弹窗

/** 删除按钮操作 */
const handleDelete = async (id: number) => {
  try {
    // 删除的二次确认
    const result: unknown = await ElMessageBox.prompt('作废不可恢复，只允许未预占且未核销的券。请输入原因。','显式作废权益',{inputValidator: value => !!value?.trim() || '必须填写原因'})
    if(typeof result !== 'object' || result === null || !('value' in result))return
    // 发起删除
    await UserApi.invalidateUser(id,String(result.value))
    message.success('权益已显式作废，记录及审计保留')
    // 刷新列表
   await getList(couponId.value)
  } catch {}
}
const getList = async(id?:number) => {
  if(id===undefined)return
  tableData.value = await UserApi.getUserList(id)
}
</script>
<style scoped>
</style>
