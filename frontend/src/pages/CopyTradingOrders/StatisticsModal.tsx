import { useEffect, useState } from 'react'
import { Modal, Row, Col, Statistic, Spin, message, Table, Tag, Divider, Alert } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import { formatUSDC } from '../../utils'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import type { CopyExecutionEvent, CopyLivePosition, CopyTradingStatistics } from '../../types'
import CopyTradingRiskSeatbeltPanel from '../../components/CopyTradingRiskSeatbeltPanel'

interface StatisticsModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
}

const StatisticsModal: React.FC<StatisticsModalProps> = ({
  open,
  onClose,
  copyTradingId
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [statistics, setStatistics] = useState<CopyTradingStatistics | null>(null)
  
  useEffect(() => {
    if (open && copyTradingId) {
      fetchStatistics()
    }
  }, [open, copyTradingId])
  
  const fetchStatistics = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const response = await apiService.statistics.detail({ copyTradingId: parseInt(copyTradingId) })
      if (response.data.code === 0 && response.data.data) {
        setStatistics(response.data.data)
      } else {
        message.error(response.data.msg || t('copyTradingOrders.fetchStatisticsFailed') || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingOrders.fetchStatisticsFailed') || '获取统计信息失败')
    } finally {
      setLoading(false)
    }
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

  const statusColor: Record<string, string> = {
    FILLED: 'success',
    SUBMITTED: 'processing',
    DETECTED: 'default',
    PENDING: 'warning',
    FILTERED: 'orange',
    FAILED: 'error',
    SKIPPED: 'default',
    NETTED: 'purple'
  }

  const statusText: Record<string, string> = {
    FILLED: '已成交',
    SUBMITTED: '已送单',
    DETECTED: '已侦测',
    PENDING: '累积中',
    FILTERED: '已过滤',
    FAILED: '失败',
    SKIPPED: '已跳过',
    NETTED: '已抵消'
  }

  const liveDetails = statistics ? (
    <div>
      <Divider orientation="left">实盘事件统计</Divider>
      {statistics.eventStats?.total === 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="事件账本从本版本部署后开始记录；旧订单仍会显示在目前持仓。"
        />
      )}
      <Row gutter={[12, 12]}>
        <Col xs={12} sm={8} md={4}><Statistic title="今日讯号" value={statistics.eventStats?.today ?? 0} /></Col>
        <Col xs={12} sm={8} md={4}><Statistic title="已成交" value={statistics.eventStats?.filled ?? 0} valueStyle={{ color: '#3f8600' }} /></Col>
        <Col xs={12} sm={8} md={4}><Statistic title="累积中" value={statistics.eventStats?.pending ?? 0} valueStyle={{ color: '#d48806' }} /></Col>
        <Col xs={12} sm={8} md={4}><Statistic title="已过滤" value={statistics.eventStats?.filtered ?? 0} valueStyle={{ color: '#d46b08' }} /></Col>
        <Col xs={12} sm={8} md={4}><Statistic title="失败" value={statistics.eventStats?.failed ?? 0} valueStyle={{ color: '#cf1322' }} /></Col>
        <Col xs={12} sm={8} md={4}><Statistic title="全部事件" value={statistics.eventStats?.total ?? 0} /></Col>
      </Row>

      <Divider orientation="left">目前实盘持仓</Divider>
      <Table<CopyLivePosition>
        rowKey={(row) => `${row.marketId}-${row.outcomeIndex}`}
        size="small"
        pagination={false}
        scroll={{ x: 900 }}
        locale={{ emptyText: '目前没有归属于此跟单配置的实盘持仓' }}
        dataSource={statistics.livePositions ?? []}
        columns={[
          {
            title: '市场',
            dataIndex: 'marketTitle',
            width: 300,
            render: (_value, row) => (
              row.marketSlug
                ? <a href={`https://polymarket.com/event/${row.marketSlug}`} target="_blank" rel="noreferrer">{row.marketTitle || row.marketId}</a>
                : <span>{row.marketTitle || row.marketId}</span>
            )
          },
          { title: '结果', dataIndex: 'outcome', width: 90, render: (value, row) => value || `#${row.outcomeIndex ?? '-'}` },
          { title: 'Shares', dataIndex: 'quantity', width: 110, render: value => formatUSDC(value) },
          { title: '均价', dataIndex: 'averageCost', width: 90, render: value => formatUSDC(value) },
          { title: '现价', dataIndex: 'currentPrice', width: 90, render: (value, row) => row.quoteStatus === 'AVAILABLE' ? formatUSDC(value) : <Tag color="warning">报价缺失</Tag> },
          { title: '成本', dataIndex: 'cost', width: 110, render: value => `$${formatUSDC(value)}` },
          { title: '市值', dataIndex: 'marketValue', width: 110, render: value => `$${formatUSDC(value)}` },
          { title: '未实现', dataIndex: 'unrealizedPnl', width: 110, render: value => <span style={{ color: getPnlColor(value) }}>${formatUSDC(value)}</span> }
        ]}
      />

      <Divider orientation="left">最近实盘事件</Divider>
      <Table<CopyExecutionEvent>
        rowKey="id"
        size="small"
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 1150 }}
        locale={{ emptyText: '尚无实盘事件；启用配置后收到 Leader 新讯号就会显示在这里' }}
        dataSource={statistics.recentEvents ?? []}
        columns={[
          { title: '时间', dataIndex: 'eventTime', width: 165, render: value => new Date(value).toLocaleString() },
          { title: '动作', dataIndex: 'action', width: 75, render: value => <Tag color={value === 'BUY' ? 'blue' : 'red'}>{value}</Tag> },
          { title: '状态', dataIndex: 'status', width: 90, render: value => <Tag color={statusColor[value] || 'default'}>{statusText[value] || value}</Tag> },
          {
            title: '市场',
            dataIndex: 'marketTitle',
            width: 300,
            render: (_value, row) => (
              row.marketSlug
                ? <a href={`https://polymarket.com/event/${row.marketSlug}`} target="_blank" rel="noreferrer">{row.marketTitle || row.marketId}</a>
                : <span>{row.marketTitle || row.marketId}</span>
            )
          },
          { title: '金额', dataIndex: 'notional', width: 100, render: value => `$${formatUSDC(value)}` },
          { title: 'Shares', dataIndex: 'quantity', width: 100, render: value => formatUSDC(value) },
          { title: '执行价', dataIndex: 'executionPrice', width: 90, render: (value, row) => value ? formatUSDC(value) : formatUSDC(row.leaderPrice || '0') },
          { title: '原因 / 订单', dataIndex: 'reason', width: 360, render: (value, row) => <span>{value || row.orderId || '-'}</span> }
        ]}
      />
    </div>
  ) : null
  
  
  return (
    <Modal
      title={t('copyTradingOrders.statistics') || '跟单关系统计'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <Spin size="large" />
        </div>
      ) : !statistics ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>{t('copyTradingOrders.noStatistics') || '暂无统计数据'}</p>
        </div>
      ) : isMobile ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalBuyOrders') || '总买入订单数'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowUpOutlined style={{ color: '#1890ff', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{statistics.totalBuyOrders}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalSellOrders') || '总卖出订单数'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowDownOutlined style={{ color: '#ff4d4f', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{statistics.totalSellOrders}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalBuyAmount') || '总买入金额'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowUpOutlined style={{ color: '#1890ff', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>${formatUSDC(statistics.totalBuyAmount)}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalSellAmount') || '总卖出金额'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowDownOutlined style={{ color: '#ff4d4f', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>${formatUSDC(statistics.totalSellAmount)}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.currentPositionCost') || '当前持仓成本'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.currentPositionCost)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.currentPositionValue') || '当前持仓市值'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.currentPositionValue)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalPnl') || '总盈亏（含未实现）'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: 'bold', color: getPnlColor(statistics.totalPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>${formatUSDC(statistics.totalPnl)}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalRealizedPnl') || '总已实现盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.totalRealizedPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalRealizedPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>${formatUSDC(statistics.totalRealizedPnl)}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalUnrealizedPnl') || '总未实现盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.totalUnrealizedPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalUnrealizedPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>${formatUSDC(statistics.totalUnrealizedPnl)}</span>
            </div>
          </div>
          <CopyTradingRiskSeatbeltPanel statistics={statistics} onApplied={fetchStatistics} compact />
          {liveDetails}
        </div>
      ) : (
        <div>
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalBuyOrders') || '总买入订单数'}
                value={statistics.totalBuyOrders}
                prefix={<ArrowUpOutlined style={{ color: '#1890ff' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalSellOrders') || '总卖出订单数'}
                value={statistics.totalSellOrders}
                prefix={<ArrowDownOutlined style={{ color: '#ff4d4f' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalBuyAmount') || '总买入金额'}
                value={formatUSDC(statistics.totalBuyAmount)}
                prefix={<><ArrowUpOutlined style={{ color: '#1890ff' }} /> $</>}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalSellAmount') || '总卖出金额'}
                value={formatUSDC(statistics.totalSellAmount)}
                prefix={<><ArrowDownOutlined style={{ color: '#ff4d4f' }} /> $</>}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.currentPositionCost') || '当前持仓成本'}
                value={formatUSDC(statistics.currentPositionCost)}
                suffix="USDC"
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.currentPositionValue') || '当前持仓市值'}
                value={formatUSDC(statistics.currentPositionValue)}
                suffix="USDC"
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalPnl') || '总盈亏（含未实现）'}
                value={formatUSDC(statistics.totalPnl)}
                valueStyle={{ color: getPnlColor(statistics.totalPnl) }}
                prefix={<>{getPnlIcon(statistics.totalPnl)} $</>}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalRealizedPnl') || '总已实现盈亏'}
                value={formatUSDC(statistics.totalRealizedPnl)}
                valueStyle={{ color: getPnlColor(statistics.totalRealizedPnl) }}
                prefix={<>{getPnlIcon(statistics.totalRealizedPnl)} $</>}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalUnrealizedPnl') || '总未实现盈亏'}
                value={formatUSDC(statistics.totalUnrealizedPnl)}
                valueStyle={{ color: getPnlColor(statistics.totalUnrealizedPnl) }}
                prefix={<>{getPnlIcon(statistics.totalUnrealizedPnl)} $</>}
              />
            </Col>
          </Row>
          <CopyTradingRiskSeatbeltPanel statistics={statistics} onApplied={fetchStatistics} compact />
          {liveDetails}
        </div>
      )}
    </Modal>
  )
}

export default StatisticsModal
