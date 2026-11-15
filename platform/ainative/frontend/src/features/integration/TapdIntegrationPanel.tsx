import { Alert, Button, Form, Input, message, Space, Table } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { SurfaceCard } from '@/shared/ui/PageShell';

interface TapdConnection {
  projectId: string;
  apiUser: string;
  apiPassword: string;
  commentAuthor: string;
}

interface TapdBinding {
  id: number;
  projectId: string;
  enabled: boolean;
  lastSuccessAt: string | null;
  syncFailed: boolean;
}

const BINDINGS = '/api/integrations/tapd/bindings';

export function TapdIntegrationPanel() {
  const { t } = useTranslation('integration');
  const [form] = Form.useForm<TapdConnection>();
  useLocalizedValidation(form);
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  const client = useQueryClient();
  const queryKey = ['tapd-bindings', workspaceId];
  const bindings = useQuery({
    queryKey,
    queryFn: async () => (await apiClient.get<TapdBinding[]>(BINDINGS)).data,
  });
  const refresh = () => client.invalidateQueries({ queryKey });
  const showError = (error: Error) => message.error(error.message);
  const test = useMutation({
    mutationFn: () => apiClient.post<boolean>(BINDINGS + '/test', form.getFieldsValue()),
    onSuccess: () => message.success(t('tapd.testSucceeded')),
    onError: showError,
  });
  const save = useMutation({
    mutationFn: () => apiClient.post<TapdBinding>(BINDINGS, form.getFieldsValue()),
    onSuccess: () => {
      form.resetFields(['apiPassword']);
      message.success(t('tapd.saved'));
      void refresh();
    },
    onError: showError,
  });
  const toggle = useMutation({
    mutationFn: (binding: TapdBinding) => apiClient.put<TapdBinding>(BINDINGS + '/' + binding.id + '/enabled',
      { enabled: !binding.enabled }),
    onSuccess: refresh,
    onError: showError,
  });
  const sync = useMutation({
    mutationFn: async (id: number) => (await apiClient.post<{ imported: number; updated: number }>(
      BINDINGS + '/' + id + '/sync-now', {})).data,
    onSuccess: (result) => {
      message.success(t('tapd.synced', { imported: result.imported, updated: result.updated }));
      void refresh();
    },
    onError: showError,
  });

  const required = [{ required: true, message: t('tapd.required') }];
  const busy = save.isPending || test.isPending;

  return (
    <div className="cfds-list-layout">
      <SurfaceCard title={t('tapd.connection')} className="ant-card">
        <Form form={form} layout="vertical" className="cfds-form" onFinish={() => save.mutate()}>
          <Form.Item label={t('tapd.projectId')} name="projectId"
            rules={[...required, { pattern: /^[1-9][0-9]{0,19}$/, message: t('tapd.projectInvalid') }]}>
            <Input inputMode="numeric" maxLength={20} />
          </Form.Item>
          <Form.Item label={t('tapd.apiUser')} name="apiUser" rules={required}>
            <Input autoComplete="off" maxLength={128} />
          </Form.Item>
          <Form.Item label={t('tapd.apiPassword')} name="apiPassword" rules={required}>
            <Input.Password autoComplete="new-password" maxLength={2048} />
          </Form.Item>
          <Form.Item label={t('tapd.commentAuthor')} name="commentAuthor" rules={required}>
            <Input maxLength={64} />
          </Form.Item>
          <Alert type="info" showIcon message={t('tapd.importScope')} />
          <Space style={{ marginTop: 12 }}>
            <Button disabled={busy} loading={test.isPending}
              onClick={() => { void form.validateFields().then(() => test.mutate(), () => undefined); }}>
              {t('tapd.test')}
            </Button>
            <Button type="primary" htmlType="submit" disabled={busy} loading={save.isPending}>
              {t('tapd.save')}
            </Button>
          </Space>
        </Form>
      </SurfaceCard>
      <SurfaceCard title={t('tapd.bindings')} className="ant-card">
        {bindings.isError && <Alert type="error" showIcon message={t('tapd.loadFailed')} />}
        <div className="cfds-table-wrap">
          <Table<TapdBinding> rowKey={(row) => String(row.id)} loading={bindings.isLoading}
            dataSource={bindings.data ?? []} pagination={false} columns={[
              { title: t('tapd.projectId'), dataIndex: 'projectId' },
              { title: t('tapd.state'), render: (_, row) =>
                row.enabled ? t('tapd.enabled') : t('tapd.disabled') },
              { title: t('tapd.lastSync'), render: (_, row) =>
                row.syncFailed ? t('tapd.syncFailed') : row.lastSuccessAt ? formatDateTime(row.lastSuccessAt) : t('tapd.notSynced') },
              { title: t('tapd.actions'), render: (_, row) => (
                <Space>
                  <Button size="small" disabled={!row.enabled || sync.isPending || toggle.isPending}
                    onClick={() => sync.mutate(row.id)}>{t('tapd.syncNow')}</Button>
                  <Button size="small" disabled={sync.isPending || toggle.isPending}
                    onClick={() => toggle.mutate(row)}>{row.enabled ? t('tapd.pause') : t('tapd.resume')}</Button>
                </Space>
              ) },
            ]} />
        </div>
      </SurfaceCard>
    </div>
  );
}
