import React, { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { lang } from '../../languages'
import { getSocket } from '../App'

import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'

const TwoFactorSetup: React.FC<{ uri: string, secret: string }> = ({ uri, secret }) => {
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const verify = () => {
    if (!/^\d{6}$/.test(code)) { setError(lang.twoFactorInvalid); return }
    setBusy(true)
    const io = getSocket()
    if (!io) { setError(lang.failedToConnect); setBusy(false); return }
    io.emit('NekoMaid:twoFactor:verify', code, (ok: boolean) => {
      setBusy(false)
      if (ok) {
        // Secret saved server-side; reload to reconnect with the now-required OTP.
        window.location.reload()
      } else {
        setError(lang.twoFactorInvalid)
      }
    })
  }

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', p: 2 }}>
      <Paper sx={{ p: 4, maxWidth: 420, width: '100%' }}>
        <Typography variant='h5' gutterBottom>{lang.twoFactorSetup.title}</Typography>
        <Typography variant='body2' color='textSecondary' gutterBottom>
          {lang.twoFactorSetup.description}
        </Typography>
        <Box sx={{ textAlign: 'center', my: 2 }}>
          <QRCodeSVG value={uri} size={200} />
        </Box>
        <Typography variant='body2' sx={{ wordBreak: 'break-all', mb: 1 }}>
          <b>{lang.twoFactorSetup.secretLabel}:</b> {secret}
        </Typography>
        <Typography variant='body2' color='textSecondary' sx={{ mb: 2 }}>
          {lang.twoFactorSetup.hint}
        </Typography>
        <TextField
          fullWidth
          label={lang.twoFactorCode}
          value={code}
          onChange={e => setCode(e.target.value)}
          inputProps={{ inputMode: 'numeric', maxLength: 6 }}
          error={!!error}
          helperText={error}
        />
        <Button fullWidth variant='contained' sx={{ mt: 2 }} disabled={busy} onClick={verify}>
          {busy ? lang.actionRunning : lang.twoFactorSetup.verify}
        </Button>
      </Paper>
    </Box>
  )
}

export default TwoFactorSetup
