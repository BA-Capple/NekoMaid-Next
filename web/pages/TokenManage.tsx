import React, { useEffect, useState } from 'react'
import { usePlugin } from '../Context'
import { lang } from '../../languages'
import { useNavigate } from 'react-router-dom'

import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import Grid from '@mui/material/Grid'

interface TokenInfo {
  name: string
  player?: string
  token: string
  permissions: string[]
  allowNo2fa?: boolean
}

const FEATURES: Array<[string, string]> = [
  ['dashboard', '仪表盘'],
  ['playerList', '玩家列表(查看)'],
  ['players', '玩家管理(封禁/白名单)'],
  ['worlds', '世界'],
  ['profiler', '性能'],
  ['scheduler', '任务(查看)'],
  ['entity', '实体(查看)'],
  ['block', '方块(查看)'],
  ['terminal', '终端命令'],
  ['plugins', '插件管理'],
  ['files', '文件管理'],
  ['config', '服务器/插件设置'],
  ['editors', '编辑(NBT)'],
  ['vault', '经济/权限'],
  ['inventory', '背包']
]

const TokenManage: React.FC = () => {
  const plugin = usePlugin()
  const navigate = useNavigate()
  const [tokens, setTokens] = useState<TokenInfo[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [perms, setPerms] = useState<Set<string>>(new Set())
  const [allowNo2fa, setAllowNo2fa] = useState(false)
  const [denied, setDenied] = useState(false)
  const [self, setSelf] = useState<TokenInfo | null>(null)
  const [saving, setSaving] = useState(false)

  const refresh = () => {
    plugin.emit('token:list', (data: any) => {
      if (Array.isArray(data)) setTokens(data as TokenInfo[])
      else {
        setDenied(true)
        plugin.emit('token:self', (mine: any) => setSelf(mine && typeof mine === 'object' ? mine as TokenInfo : null))
      }
    })
  }
  useEffect(() => { refresh() }, [])

  const select = (name: string) => {
    setSelected(name)
    const t = tokens.find(it => it.name === name)
    setPerms(new Set((t && Array.isArray(t.permissions) ? t.permissions : [])))
    setAllowNo2fa(!!t?.allowNo2fa)
  }

  const toggle = (key: string) => {
    setPerms(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const save = () => {
    if (!selected) return
    setSaving(true)
    plugin.emit('token:update', (ok: boolean) => {
      setSaving(false)
      if (ok) refresh()
    }, selected, Array.from(perms), allowNo2fa)
  }

  if (denied) {
    if (self) {
      return <Box p={3}>
        <Typography variant='h5' gutterBottom>{lang.tokenManage.title}</Typography>
        <Paper sx={{ p: 2 }}>
          <Typography variant='body1'><b>Token:</b> {self.token}</Typography>
          {self.player && <Typography variant='body2' color='textSecondary'>Player: {self.player}</Typography>}
          <Typography variant='body2' color='textSecondary'>allowNo2fa: {self.allowNo2fa ? 'true' : 'false'}</Typography>
          <Typography variant='body2' sx={{ mt: 1 }}>{lang.tokenManage.permission}:</Typography>
          <Box mt={1}>
            {FEATURES.filter(([key]) => (self.permissions || []).includes(key)).map(([key, label]) => (
              <Chip key={key} label={label} size='small' sx={{ mr: 0.5, mb: 0.5 }} />
            ))}
          </Box>
        </Paper>
      </Box>
    }
    return <Box p={3}><Typography>{lang.noPermission}</Typography></Box>
  }

  return (
    <Box p={3}>
      <Typography variant='h5' gutterBottom>{lang.tokenManage.title}</Typography>
      <Typography variant='body2' color='textSecondary' gutterBottom>{lang.tokenManage.description}</Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} md={4}>
          <Paper>
            <List dense>
              {tokens.map(t => (
                <ListItem key={t.name} disablePadding>
                  <ListItemButton selected={selected === t.name} onClick={() => select(t.name)}>
                    <ListItemText primary={t.name}
                      secondary={t.player ? `${t.player} · ${t.token}` : t.token} />
                  </ListItemButton>
                </ListItem>
              ))}
              {tokens.length === 0 && <ListItem><ListItemText primary={lang.noData} /></ListItem>}
            </List>
          </Paper>
        </Grid>
        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 2 }}>
            {selected ? <>
              <Box mb={2}><Chip label={selected} /></Box>
              <Grid container spacing={1}>
                {FEATURES.map(([key, label]) => (
                  <Grid item xs={6} sm={4} key={key}>
                    <FormControlLabel control={<Checkbox checked={perms.has(key)} onChange={() => toggle(key)} />}
                      label={label} />
                  </Grid>
                ))}
              </Grid>
              <Box mt={2}>
                <FormControlLabel control={<Checkbox checked={allowNo2fa} onChange={e => setAllowNo2fa(e.target.checked)} />}
                  label={lang.tokenManage.allowNo2fa} />
                {allowNo2fa && (
                  <Typography variant='body2' color='error' sx={{ mt: 1 }}>
                    {lang.tokenManage.allowNo2faWarning}
                  </Typography>
                )}
              </Box>
              <Box mt={2}>
                <Button variant='contained' color='primary' disabled={saving} onClick={save}>
                  {saving ? lang.actionRunning : lang.actionSave}
                </Button>
              </Box>
            </> : <Typography color='textSecondary'>{lang.tokenManage.selectHint}</Typography>}
          </Paper>
        </Grid>
      </Grid>
      <Box mt={2}><Button onClick={() => navigate('/NekoMaid/dashboard')}>{lang.back}</Button></Box>
    </Box>
  )
}

export default TokenManage
