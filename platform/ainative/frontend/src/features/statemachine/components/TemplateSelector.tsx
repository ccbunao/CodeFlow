import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { Select, Button, Tag, Modal, Form, Input, Popconfirm } from 'antd';
import { StarOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusTemplate, WorkType } from '../types';

interface Props {
  createOpen: boolean;
  onCloseCreate: () => void;
  templates: StatusTemplate[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate: (name: string) => void;
  onSetDefault: (id: number) => void;
  onDelete: (id: number) => void;
  workType: WorkType;
  creating?: boolean;
}

export function TemplateSelector({ createOpen, creating = false, onCloseCreate, templates, selectedId, onSelect, onCreate, onSetDefault, onDelete }: Props) {
  const { t } = useTranslation('statemachine');
  const [form] = Form.useForm();
  useLocalizedValidation(form, createOpen);

  const selected = templates.find((t) => t.id === selectedId);

  const handleCreate = () => {
    if (creating) return; // 防重复提交:上一次请求未落地前不再触发
    form.validateFields().then(({ name }) => {
      onCreate(name);
      onCloseCreate();
      form.resetFields();
    }, () => {});
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
      <span style={{ fontSize: 14, color: 'var(--cf-muted)' }}>{t('templates.current')}</span>
      <Select
        aria-label={t('templates.current')}
        value={selectedId}
        onChange={onSelect}
        style={{ minWidth: 200 }}
        options={templates.map((template) => ({
          value: template.id,
          label: (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, height: '100%' }}>
              <span>{template.name}</span>
              {template.isDefault && <Tag color="blue" style={{ fontSize: 10, lineHeight: '18px' }}>{t('templates.default')}</Tag>}
            </span>
          ),
        }))}
      />
      {selected && !selected.isDefault && (
        <Button icon={<StarOutlined aria-hidden="true" />} onClick={() => onSetDefault(selected.id)}>{t('templates.setDefault')}</Button>
      )}
      {selected && !selected.isDefault && (
        <Popconfirm title={t('templates.confirmDelete')} onConfirm={() => onDelete(selected.id)} okText={t('common.delete')} cancelText={t('common.cancel')}>
          <Button danger icon={<DeleteOutlined aria-hidden="true" />}>{t('common.delete')}</Button>
        </Popconfirm>
      )}

      <Modal closable={{ 'aria-label': t('common.close') }} title={t('actions.createTemplate')} open={createOpen} onOk={handleCreate} onCancel={() => onCloseCreate()} confirmLoading={creating} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label={t('templates.name')} rules={[{ required: true, message: t('templates.nameRequired') }]}>
            <Input placeholder={t('templates.namePlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
