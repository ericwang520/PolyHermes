import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Dropdown, Spin, List, Empty, Tooltip, Modal, Row, Col, Statistic, Alert } from 'antd'
import { PlusOutlined, DeleteOutlined, BarChartOutlined, UnorderedListOutlined, ArrowUpOutlined, ArrowDownOutlined, EditOutlined, WalletOutlined, UserOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { MenuProps } from 'antd'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CopyTrading, Leader, CopyTradingStatistics, CopySimulationSummary } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'
import CopyTradingOrdersModal from './CopyTradingOrders/index'
import StatisticsModal from './CopyTradingOrders/StatisticsModal'
import FilteredOrdersModal from './CopyTradingOrders/FilteredOrdersModal'
import EditModal from './CopyTradingOrders/EditModal'
import AddModal from './CopyTradingOrders/AddModal'
import LeaderSelect from '../components/LeaderSelect'

const { Option } = Select

const CopyTradingList: React.FC = () => {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [copyTradings, setCopyTradings] = useState<CopyTrading[]>([])
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [loading, setLoading] = useState(false)
  const [statisticsMap, setStatisticsMap] = useState<Record<number, CopyTradingStatistics>>({})
  const [simulationMap, setSimulationMap] = useState<Record<number, CopySimulationSummary>>({})
  const [loadingStatistics, setLoadingStatistics] = useState<Set<number>>(new Set())
  const [filters, setFilters] = useState<{
    accountId?: number
    leaderId?: number
    enabled?: boolean
  }>(() => {
    const leaderIdParam = searchParams.get('leaderId')
    if (leaderIdParam) {
      const leaderId = parseInt(leaderIdParam, 10)
      if (!isNaN(leaderId)) return { leaderId }
    }
    return {}
  })

  // Modal 状态
  const [ordersModalOpen, setOrdersModalOpen] = useState(false)
  const [ordersModalCopyTradingId, setOrdersModalCopyTradingId] = useState<string>('')
  const [ordersModalTab, setOrdersModalTab] = useState<'buy' | 'sell' | 'matched'>('buy')
  const [statisticsModalOpen, setStatisticsModalOpen] = useState(false)
  const [statisticsModalCopyTradingId, setStatisticsModalCopyTradingId] = useState<string>('')
  const [simulationModalOpen, setSimulationModalOpen] = useState(false)
  const [simulationModalCopyTradingId, setSimulationModalCopyTradingId] = useState<number | null>(null)
  const [filteredOrdersModalOpen, setFilteredOrdersModalOpen] = useState(false)
  const [filteredOrdersModalCopyTradingId, setFilteredOrdersModalCopyTradingId] = useState<string>('')
  const [editModalOpen, setEditModalOpen] = useState(false)
  const [editModalCopyTradingId, setEditModalCopyTradingId] = useState<string>('')
  const [addModalOpen, setAddModalOpen] = useState(false)
  
  // 从 URL 读取 leaderId 并应用筛选（如从 Leader 管理页跳转过来）
  useEffect(() => {
    const leaderIdParam = searchParams.get('leaderId')
    if (leaderIdParam) {
      const leaderId = parseInt(leaderIdParam, 10)
      if (!isNaN(leaderId)) {
        setFilters(prev => ({ ...prev, leaderId }))
      }
    }
  }, [searchParams])

  useEffect(() => {
    fetchAccounts()
    fetchLeaders()
    fetchCopyTradings()
  }, [])

  useEffect(() => {
    fetchCopyTradings()
  }, [filters])
  
  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      console.error('获取 Leader 列表失败:', error)
    }
  }
  
  const fetchCopyTradings = async () => {
    setLoading(true)
    try {
      const response = await apiService.copyTrading.list(filters)
      if (response.data.code === 0 && response.data.data) {
        const list = response.data.data.list || []
        setCopyTradings(list)
        setStatisticsMap({})
        setSimulationMap({})
        // 实盘读取真实订单统计；模拟盘读取独立的模拟账本
        list.forEach((ct: CopyTrading) => {
          if (ct.executionMode === 'PAPER') {
            fetchSimulationSummary(ct.id)
          } else {
            fetchStatistics(ct.id)
          }
        })
      } else {
        message.error(response.data.msg || t('copyTradingList.fetchFailed') || '获取跟单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.fetchFailed') || '获取跟单列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const fetchStatistics = async (copyTradingId: number) => {
    // 如果正在加载或已有数据，跳过
    if (loadingStatistics.has(copyTradingId) || statisticsMap[copyTradingId]) {
      return
    }
    
    setLoadingStatistics(prev => new Set(prev).add(copyTradingId))
    try {
      const response = await apiService.statistics.detail({ copyTradingId })
      if (response.data.code === 0 && response.data.data) {
        setStatisticsMap(prev => ({
          ...prev,
          [copyTradingId]: response.data.data
        }))
      }
    } catch (error: any) {
      console.error(`获取跟单统计失败: copyTradingId=${copyTradingId}`, error)
    } finally {
      setLoadingStatistics(prev => {
        const next = new Set(prev)
        next.delete(copyTradingId)
        return next
      })
    }
  }

  const fetchSimulationSummary = async (copyTradingId: number) => {
    setLoadingStatistics(prev => new Set(prev).add(copyTradingId))
    try {
      const response = await apiService.copyTrading.simulationSummary({ copyTradingId })
      if (response.data.code === 0 && response.data.data) {
        setSimulationMap(prev => ({
          ...prev,
          [copyTradingId]: response.data.data as CopySimulationSummary
        }))
      }
    } catch (error: any) {
      console.error(`获取模拟跟单统计失败: copyTradingId=${copyTradingId}`, error)
    } finally {
      setLoadingStatistics(prev => {
        const next = new Set(prev)
        next.delete(copyTradingId)
        return next
      })
    }
  }

  const openStatistics = (record: CopyTrading) => {
    if (record.executionMode === 'PAPER') {
      setSimulationModalCopyTradingId(record.id)
      setSimulationModalOpen(true)
      fetchSimulationSummary(record.id)
      return
    }
    setStatisticsModalCopyTradingId(record.id.toString())
    setStatisticsModalOpen(true)
  }
  
  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const getPnlIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }
  
  const formatPercent = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '-'
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }
  
  const handleToggleStatus = async (copyTrading: CopyTrading) => {
    try {
      const response = await apiService.copyTrading.updateStatus({
        copyTradingId: copyTrading.id,
        enabled: !copyTrading.enabled
      })
      if (response.data.code === 0) {
        message.success(copyTrading.enabled ? (t('copyTradingList.stopSuccess') || '停止跟单成功') : (t('copyTradingList.startSuccess') || '开启跟单成功'))
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || t('copyTradingList.updateStatusFailed') || '更新跟单状态失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.updateStatusFailed') || '更新跟单状态失败')
    }
  }
  
  const handleDelete = async (copyTradingId: number) => {
    try {
      const response = await apiService.copyTrading.delete({ copyTradingId })
      if (response.data.code === 0) {
        message.success(t('copyTradingList.deleteSuccess') || '删除跟单成功')
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || t('copyTradingList.deleteFailed') || '删除跟单失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.deleteFailed') || '删除跟单失败')
    }
  }
  
  const columns = [
    {
      title: t('copyTradingList.configName') || '配置名',
      key: 'configName',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
          {record.configName || t('copyTradingList.configNameNotProvided') || '未提供'}
        </div>
      )
    },
    {
      title: t('copyTradingList.wallet') || '钱包',
      key: 'account',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.accountName || `${t('copyTradingList.account') || '账户'} ${record.accountId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.walletAddress.slice(0, 4)}...${record.walletAddress.slice(-3)}`
              : `${record.walletAddress.slice(0, 6)}...${record.walletAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: t('copyTradingList.copyMode') || '跟单模式',
      key: 'copyMode',
      width: isMobile ? 100 : 120,
      render: (_: any, record: CopyTrading) => (
        <Space direction="vertical" size={2}>
          <Tag color={record.executionMode === 'PAPER' ? 'purple' : 'red'}>
            {record.executionMode === 'PAPER' ? '模擬' : '實盤'}
          </Tag>
          <Tag color={record.copyMode === 'RATIO' ? 'blue' : 'green'}>
            {record.copyMode === 'RATIO'
              ? `${t('copyTradingList.ratioMode') || '比例'} ${(parseFloat(record.copyRatio || '0') * 100).toFixed(2).replace(/\.0+$/, '')}%`
              : `${t('copyTradingList.fixedAmountMode') || '固定'} ${formatUSDC(record.fixedAmount || '0')}`
            }
          </Tag>
        </Space>
      )
    },
    {
      title: t('copyTradingList.leader') || 'Leader',
      key: 'leader',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.leaderName || `Leader ${record.leaderId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.leaderAddress.slice(0, 4)}...${record.leaderAddress.slice(-3)}`
              : `${record.leaderAddress.slice(0, 6)}...${record.leaderAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: t('common.status') || '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: isMobile ? 80 : 100,
      render: (enabled: boolean, record: CopyTrading) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggleStatus(record)}
          checkedChildren={t('copyTradingList.enabled') || '开启'}
          unCheckedChildren={t('copyTradingList.disabled') || '停止'}
        />
      )
    },
    {
      title: '总盈亏（含未实现）',
      key: 'totalPnl',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => {
        if (record.executionMode === 'PAPER') {
          const simulation = simulationMap[record.id]
          if (!simulation) {
            return loadingStatistics.has(record.id) ? (
              <span style={{ fontSize: isMobile ? 11 : 12 }}>{t('common.loading') || '加载中...'}</span>
            ) : (
              <span style={{ fontSize: isMobile ? 11 : 12 }}>尚未成交</span>
            )
          }
          const totalPnl = simulation.totalPnl
          if (totalPnl == null) {
            return (
              <Tooltip title="有持倉缺少可用價格，因此不顯示推測性的總盈虧">
                <span style={{ color: '#8c8c8c' }}>估值未知</span>
              </Tooltip>
            )
          }
          const initialCash = parseFloat(simulation.initialCash)
          const pnlPercent = initialCash > 0 ? (parseFloat(totalPnl) / initialCash * 100).toString() : '0'
          return (
            <div>
              <div style={{ color: getPnlColor(totalPnl), fontWeight: 500, display: 'flex', alignItems: 'center', gap: 4 }}>
                {getPnlIcon(totalPnl)}
                ${formatUSDC(totalPnl)}
              </div>
              {!isMobile && (
                <div style={{ fontSize: 12, color: getPnlColor(pnlPercent), marginTop: 4 }}>
                  {formatPercent(pnlPercent)}
                </div>
              )}
            </div>
          )
        }
        const stats = statisticsMap[record.id]
        if (!stats) {
          return loadingStatistics.has(record.id) ? (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>{t('common.loading') || '加载中...'}</span>
          ) : (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>-</span>
          )
        }
        return (
          <div>
            <div style={{ 
              color: getPnlColor(stats.totalPnl), 
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              fontSize: isMobile ? 12 : 14
            }}>
              {getPnlIcon(stats.totalPnl)}
              {isMobile ? formatUSDC(stats.totalPnl) : `$${formatUSDC(stats.totalPnl)}`}
            </div>
            {!isMobile && (
              <div style={{ 
                fontSize: 12, 
                color: getPnlColor(stats.totalPnlPercent),
                marginTop: 4
              }}>
                {formatPercent(stats.totalPnlPercent)}
              </div>
            )}
          </div>
        )
      }
    },
    {
      title: t('common.actions') || '操作',
      key: 'action',
      width: isMobile ? 100 : 160,
      fixed: 'right' as const,
      render: (_: any, record: CopyTrading) => {
        const menuItems: MenuProps['items'] = [
          {
            key: 'matchedOrders',
            label: t('copyTradingList.matchedOrders') || '已成交订单',
            icon: <UnorderedListOutlined />,
            onClick: () => {
              setOrdersModalCopyTradingId(record.id.toString())
              setOrdersModalTab('buy')
              setOrdersModalOpen(true)
            }
          },
          {
            key: 'filteredOrders',
            label: t('copyTradingList.filteredOrders') || '已过滤订单',
            icon: <UnorderedListOutlined />,
            onClick: () => {
              setFilteredOrdersModalCopyTradingId(record.id.toString())
              setFilteredOrdersModalOpen(true)
            }
          }
        ]

        return (
          <Space size={4}>
            <Tooltip title={t('common.edit') || '编辑'}>
              <div
                onClick={() => {
                  setEditModalCopyTradingId(record.id.toString())
                  setEditModalOpen(true)
                }}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
                onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
              >
                <EditOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
              </div>
            </Tooltip>

            <Tooltip title={t('copyTradingList.statistics') || '统计'}>
              <div
                onClick={() => {
                  openStatistics(record)
                }}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
                onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
              >
                <BarChartOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
              </div>
            </Tooltip>

            <Dropdown menu={{ items: menuItems }} trigger={['click']}>
              <Tooltip title={t('copyTradingList.orders') || '订单'}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: '32px',
                    height: '32px',
                    cursor: 'pointer',
                    borderRadius: '6px',
                    transition: 'background-color 0.2s'
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
                  onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
                >
                  <UnorderedListOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
                </div>
              </Tooltip>
            </Dropdown>

            <Popconfirm
              title={t('copyTradingList.deleteConfirm') || '确定要删除这个跟单关系吗？'}
              onConfirm={() => handleDelete(record.id)}
              okText={t('common.confirm') || '确定'}
              cancelText={t('common.cancel') || '取消'}
            >
              <Tooltip title={t('common.delete') || '删除'}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: '32px',
                    height: '32px',
                    cursor: 'pointer',
                    borderRadius: '6px',
                    transition: 'background-color 0.2s'
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#fff1f0' }}
                  onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
                >
                  <DeleteOutlined style={{ fontSize: '16px', color: '#ff4d4f' }} />
                </div>
              </Tooltip>
            </Popconfirm>
          </Space>
        )
      }
    }
  ]
  
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('copyTradingList.title') || '跟单配置管理'}</h2>
        <Tooltip title={t('copyTradingList.addCopyTrading') || '新增跟单'}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setAddModalOpen(true)}
            size={isMobile ? 'middle' : 'large'}
            style={{ borderRadius: '8px', height: isMobile ? '40px' : '48px', fontSize: isMobile ? '14px' : '16px' }}
          />
        </Tooltip>
      </div>

      <Card style={{ borderRadius: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', border: '1px solid #e8e8e8' }} bodyStyle={{ padding: isMobile ? '12px' : '24px' }}>
        <div style={{ marginBottom: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <Select
            placeholder={t('copyTradingList.filterWallet') || '筛选钱包'}
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.accountId}
            onChange={(value) => setFilters({ ...filters, accountId: value || undefined })}
          >
            {accounts.map(account => (
              <Option key={account.id} value={account.id}>
                {account.accountName || `${t('copyTradingList.account') || '账户'} ${account.id}`}
              </Option>
            ))}
          </Select>
          
          <LeaderSelect
            placeholder={t('copyTradingList.filterLeader') || '筛选 Leader'}
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.leaderId}
            onChange={(value) => setFilters({ ...filters, leaderId: value || undefined })}
            leaders={leaders}
          />
          
          <Select
            placeholder={t('common.status') || '状态'}
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.enabled}
            onChange={(value) => setFilters({ ...filters, enabled: value !== undefined ? value : undefined })}
          >
            <Option value={true}>{t('common.enabled') || '开启'}</Option>
            <Option value={false}>{t('common.disabled') || '停止'}</Option>
          </Select>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : copyTradings.length === 0 ? (
              <Empty description={t('copyTradingList.noData') || '暂无跟单配置'} />
            ) : (
              <List
                dataSource={copyTradings}
                renderItem={(record) => {
                  const stats = statisticsMap[record.id]
                  const simulation = simulationMap[record.id]
                  const pnl = record.executionMode === 'PAPER' ? simulation?.totalPnl : stats?.totalPnl
                  const initialCash = simulation ? parseFloat(simulation.initialCash) : 0
                  const pnlPercent = record.executionMode === 'PAPER'
                    ? (pnl != null && initialCash > 0 ? (parseFloat(pnl) / initialCash * 100).toString() : undefined)
                    : stats?.totalPnlPercent
                  
                  return (
                    <Card
                      key={record.id}
                      style={{
                        marginBottom: '10px',
                        borderRadius: '10px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8',
                        overflow: 'hidden'
                      }}
                      bodyStyle={{ padding: '0' }}
                    >
                      {/* 头部区域 - 配置名称 */}
                      <div style={{
                        padding: '10px 12px',
                        background: 'var(--ant-color-primary, #1677ff)',
                        color: '#fff'
                      }}>
                        <div style={{ fontSize: '15px', fontWeight: '600', marginBottom: '2px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                          <span>{record.configName || t('copyTradingList.configNameNotProvided') || '未提供'}</span>
                          <Switch
                            checked={record.enabled}
                            onChange={() => handleToggleStatus(record)}
                            checkedChildren={t('copyTradingList.enabled') || '开启'}
                            unCheckedChildren={t('copyTradingList.disabled') || '停止'}
                            size="small"
                          />
                        </div>
                        <div style={{ fontSize: '12px', opacity: '0.9' }}>
                          {record.executionMode === 'PAPER' ? '模擬 · ' : '實盤 · '}
                          {record.copyMode === 'RATIO' 
                            ? `${t('copyTradingList.ratioMode') || '比例'} ${(parseFloat(record.copyRatio || '0') * 100).toFixed(0).replace(/\.0+$/, '')}%`
                            : `${t('copyTradingList.fixedAmountMode') || '固定'} $${formatUSDC(record.fixedAmount || '0')}`
                          }
                        </div>
                      </div>

                      {/* 盈亏区域 - 常驻显示 */}
                      <div style={{
                        padding: '8px 12px',
                        backgroundColor: '#fafafa',
                        borderBottom: '1px solid #f0f0f0',
                        minHeight: '42px',
                        display: 'flex',
                        alignItems: 'center'
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                          <div>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              总盈亏（含未实现）
                            </div>
                            {pnl != null ? (
                              <div style={{ 
                                fontSize: '14px', 
                                fontWeight: '600',
                                color: getPnlColor(pnl),
                                display: 'flex',
                                alignItems: 'center',
                                gap: '4px'
                              }}>
                                {getPnlIcon(pnl)}
                                ${formatUSDC(pnl)}
                              </div>
                            ) : loadingStatistics.has(record.id) ? (
                              <Spin size="small" />
                            ) : (
                              <div style={{ fontSize: '14px', color: '#8c8c8c' }}>-</div>
                            )}
                          </div>
                          {pnlPercent != null && (
                            <div style={{ textAlign: 'right' }}>
                              <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                                {t('copyTradingList.profitRate') || '收益率'}
                              </div>
                              <div style={{ 
                                fontSize: '12px', 
                                fontWeight: '500',
                                color: getPnlColor(pnlPercent)
                              }}>
                                {formatPercent(pnlPercent)}
                              </div>
                            </div>
                          )}
                        </div>
                      </div>

                      {/* 账户和Leader信息区域 */}
                      <div style={{
                        padding: '8px 12px',
                        fontSize: '11px',
                        color: '#8c8c8c',
                        borderBottom: '1px solid #f0f0f0'
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '4px' }}>
                          <WalletOutlined style={{ fontSize: '12px', marginRight: '4px', color: '#1890ff' }} />
                          <span>{t('copyTradingList.wallet') || '账户'}: {record.accountName || `#${record.accountId}`}</span>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center' }}>
                          <UserOutlined style={{ fontSize: '12px', marginRight: '4px', color: '#722ed1' }} />
                          <span>Leader: {record.leaderName || `#${record.leaderId}`}</span>
                        </div>
                      </div>

                      {/* 图标操作栏 */}
                      <div style={{
                        padding: '8px 12px',
                        display: 'flex',
                        justifyContent: 'space-around',
                        alignItems: 'center'
                      }}>
                        <Tooltip title={t('common.edit') || '编辑'}>
                          <div
                            onClick={() => {
                              setEditModalCopyTradingId(record.id.toString())
                              setEditModalOpen(true)
                            }}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <EditOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.edit') || '编辑'}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('copyTradingList.statistics') || '统计'}>
                          <div
                            onClick={() => {
                              openStatistics(record)
                            }}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <BarChartOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('copyTradingList.statistics') || '统计'}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('copyTradingList.orders') || '订单'}>
                          <div
                            onClick={() => {
                              setOrdersModalCopyTradingId(record.id.toString())
                              setOrdersModalTab('buy')
                              setOrdersModalOpen(true)
                            }}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <UnorderedListOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('copyTradingList.orders') || '订单'}</span>
                          </div>
                        </Tooltip>

                        <Popconfirm
                          title={t('copyTradingList.deleteConfirm') || '确定要删除这个跟单关系吗？'}
                          onConfirm={() => handleDelete(record.id)}
                          okText={t('common.confirm') || '确定'}
                          cancelText={t('common.cancel') || '取消'}
                        >
                          <Tooltip title={t('common.delete') || '删除'}>
                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                              <DeleteOutlined style={{ fontSize: '18px', color: '#ff4d4f' }} />
                              <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.delete') || '删除'}</span>
                            </div>
                          </Tooltip>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                }}
              />
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={copyTradings}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`
            }}
          />
        )}
      </Card>
      
      {/* Modal 组件 */}
      <CopyTradingOrdersModal
        open={ordersModalOpen}
        onClose={() => setOrdersModalOpen(false)}
        copyTradingId={ordersModalCopyTradingId}
        defaultTab={ordersModalTab}
      />
      <StatisticsModal
        open={statisticsModalOpen}
        onClose={() => setStatisticsModalOpen(false)}
        copyTradingId={statisticsModalCopyTradingId}
      />
      <Modal
        title="模擬跟單帳本"
        open={simulationModalOpen}
        onCancel={() => setSimulationModalOpen(false)}
        footer={null}
        width={1000}
      >
        {simulationModalCopyTradingId == null || (loadingStatistics.has(simulationModalCopyTradingId) && !simulationMap[simulationModalCopyTradingId]) ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : !simulationMap[simulationModalCopyTradingId] ? (
          <Empty description="尚未收到可模擬的 Leader 交易" />
        ) : (() => {
          const summary = simulationMap[simulationModalCopyTradingId]
          return (
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
              <Alert
                type="info"
                showIcon
                message="這是獨立模擬帳本，不會解密私鑰、不會送出訂單，也不會改動真實資產。成交價採 Leader 事件價，未計入排隊與滑價。"
              />
              <Row gutter={[16, 16]}>
                <Col xs={12} md={6}><Statistic title="模擬現金" prefix="$" value={summary.cashBalance} precision={2} /></Col>
                <Col xs={12} md={6}><Statistic title="持倉估值" prefix="$" value={summary.positionValue ?? '-'} precision={2} /></Col>
                <Col xs={12} md={6}><Statistic title="已實現盈虧" prefix="$" value={summary.realizedPnl} precision={2} valueStyle={{ color: getPnlColor(summary.realizedPnl) }} /></Col>
                <Col xs={12} md={6}><Statistic title="總盈虧" prefix="$" value={summary.totalPnl ?? '-'} precision={2} valueStyle={{ color: summary.totalPnl == null ? '#8c8c8c' : getPnlColor(summary.totalPnl) }} /></Col>
              </Row>
              <div>
                <h3>模擬持倉</h3>
                <Table
                  size="small"
                  rowKey={(row) => `${row.marketId}-${row.outcomeIndex}`}
                  pagination={{ pageSize: 8 }}
                  dataSource={summary.positions}
                  columns={[
                    { title: '市場', dataIndex: 'marketId', ellipsis: true },
                    { title: '結果', dataIndex: 'outcomeIndex', width: 70 },
                    { title: '數量', dataIndex: 'quantity', width: 110, render: (value: string) => Number(value).toFixed(4) },
                    { title: '均價', dataIndex: 'averageCost', width: 90, render: (value: string) => Number(value).toFixed(4) },
                    { title: '最後價', dataIndex: 'lastPrice', width: 90, render: (value?: string) => value == null ? '-' : Number(value).toFixed(4) },
                    { title: '未實現', dataIndex: 'unrealizedPnl', width: 100, render: (value?: string) => value == null ? '-' : `$${Number(value).toFixed(2)}` }
                  ]}
                />
              </div>
              <div>
                <h3>最近模擬事件</h3>
                <Table
                  size="small"
                  rowKey="id"
                  pagination={{ pageSize: 8 }}
                  dataSource={summary.trades}
                  columns={[
                    { title: '動作', dataIndex: 'action', width: 90 },
                    { title: '狀態', dataIndex: 'status', width: 100 },
                    { title: '市場', dataIndex: 'marketId', ellipsis: true },
                    { title: '金額', dataIndex: 'notional', width: 100, render: (value: string) => `$${Number(value).toFixed(2)}` },
                    { title: '已實現', dataIndex: 'realizedPnl', width: 100, render: (value: string) => `$${Number(value).toFixed(2)}` },
                    { title: '原因', dataIndex: 'reason', ellipsis: true }
                  ]}
                />
              </div>
            </Space>
          )
        })()}
      </Modal>
      <FilteredOrdersModal
        open={filteredOrdersModalOpen}
        onClose={() => setFilteredOrdersModalOpen(false)}
        copyTradingId={filteredOrdersModalCopyTradingId}
      />
      <EditModal
        open={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        copyTradingId={editModalCopyTradingId}
        onSuccess={() => {
          fetchCopyTradings()
        }}
      />
      <AddModal
        open={addModalOpen}
        onClose={() => setAddModalOpen(false)}
        onSuccess={() => {
          fetchCopyTradings()
        }}
      />
    </div>
  )
}

export default CopyTradingList
